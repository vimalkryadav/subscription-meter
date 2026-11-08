package dev.subscriptionmeter;

import dev.subscriptionmeter.persistence.JdbcRequestQuery;
import dev.subscriptionmeter.web.RequestSearchController;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class RequestSearchTest {
    JdbcTemplate jdbc; RequestSearchController controller;
    final Instant time=Instant.now().minusSeconds(3600);
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:search-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),new ClassPathResource("accounting-schema.sql")).execute(ds);jdbc=new JdbcTemplate(ds);
        jdbc.update("INSERT INTO members(id,name,key_hash,key_prefix,rpm,concurrency_limit) VALUES('member','Example','hash','prefix',60,4)");
        controller=new RequestSearchController(new JdbcRequestQuery(jdbc,30));
    }
    void request(String id,Instant started,String model){jdbc.update("INSERT INTO requests(id,member_id,started_at,path,model,state,status_code,duration_ms,upstream_request_id,usage_seen) VALUES(?,'member',?,'/v1/messages',?,'complete',200,120,'provider-id',TRUE)",id,Timestamp.from(started),model);}
    dev.subscriptionmeter.domain.RequestQuery.Page page(String cursor,int limit,String model){return page(cursor,limit,model,"");}
    dev.subscriptionmeter.domain.RequestQuery.Page page(String cursor,int limit,String model,String family){return controller.search(time.minusSeconds(1),time.plusSeconds(60),"",model,"","","",null,"",null,null,null,"","","","","",family,limit,cursor,new MockHttpServletResponse());}
    @Test void cursorOrderRemainsStableAndRejectsDifferentFilters(){
        request("a",time,"model");request("b",time,"model");request("c",time,"model");
        var first=page("",2,"");assertThat(first.items()).extracting(r->r.get("id")).containsExactly("c","b");assertThat(first.nextCursor()).isNotNull();
        request("new",time.plusSeconds(10),"model");
        var second=page(first.nextCursor(),2,"");assertThat(second.items()).extracting(r->r.get("id")).containsExactly("a");assertThat(second.nextCursor()).isNull();
        assertThatThrownBy(()->page(first.nextCursor(),2,"changed")).hasMessageContaining("400");
        assertThatThrownBy(()->page("invalid",2,"")).hasMessageContaining("400");
    }
    @Test void exactFiltersAreParameterizedAndListsExcludePayloads(){
        request("a",time,"model");request("b",time,"other");
        assertThat(page("",25,"model").items()).extracting(r->r.get("id")).containsExactly("a");
        assertThat(page("",25,"' OR 1=1 --").items()).isEmpty();
        assertThat(page("",25,"").items().getFirst()).doesNotContainKeys("request_body","response_body","request_headers");
        assertThatThrownBy(()->page("",101,"")).hasMessageContaining("400");
    }
    @Test void fableFamilyFilterKeepsOtherModelsAndProvidersOutAndBindsCursor(){
        request("fable-one",time,"claude-fable-5-1");request("fable-two",time,"FABLE");
        request("opus",time,"claude-opus-5-5");request("foreign",time,"claude-fable-5-1");
        jdbc.update("UPDATE requests SET provider='openai' WHERE id='foreign'");
        assertThat(page("",25,"","fable").items()).extracting(r->r.get("id")).containsExactly("fable-two","fable-one");
        var first=page("",1,"","fable");
        assertThatThrownBy(()->page(first.nextCursor(),1,"","")).hasMessageContaining("400");
        assertThatThrownBy(()->page("",25,"","unknown")).hasMessageContaining("400");
    }
    @Test void legacyTimestampMismatchAndBoundedEventExportRemainReadable() throws Exception {
        request("a",time,"model");Instant captureTime=time.minusMillis(3);
        jdbc.update("INSERT INTO request_details(request_id,started_at,member_id,upstream_mode,method,path,request_body,capture_state,event_count,truncated) VALUES('a',?,'member','subscription','POST','/v1/messages','<script>untrusted</script>','complete',2,TRUE)",Timestamp.from(captureTime));
        for(int sequence=1;sequence<=3;sequence++)jdbc.update("INSERT INTO request_events(request_id,request_started_at,observed_at,sequence,elapsed_ms,event_type,event_data) VALUES('a',?,?,?,1,'delta','redacted content')",Timestamp.from(captureTime),Timestamp.from(time),sequence);
        assertThat(page("",25,"").items().getFirst().get("capture_status")).isEqualTo("truncated");
        var response=new MockHttpServletResponse();controller.export("a",response);
        var json=JsonMapper.builder().build().readTree(response.getContentAsString());
        assertThat(json.path("request").path("request_body").asString()).isEqualTo("<script>untrusted</script>");
        assertThat(json.path("events").size()).isEqualTo(2);assertThat(json.path("exportedThroughSequence").asInt()).isEqualTo(2);
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"request-a.json\"");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    @Test void absentCaptureHasHonestUnavailableOrExpiredState(){
        request("new",time,"model");assertThat(page("",25,"").items().getFirst().get("capture_status")).isEqualTo("unavailable");
        Instant old=time.minusSeconds(40L*86400);request("old",old,"model");
        var result=controller.search(old.minusSeconds(1),old.plusSeconds(1),"","","","","",null,"expired",null,null,null,"","","","","","",25,"",new MockHttpServletResponse());
        assertThat(result.items()).hasSize(1);assertThat(result.items().getFirst().get("capture_status")).isEqualTo("expired");
    }
    @Test void incompleteResponsesNeverLookLikeCompleteCapturesAndFilterMatchesDetails(){
        var query=new JdbcRequestQuery(jdbc,30);
        for(String state:List.of("partial","disconnected","interrupted","stream_error","gateway_error")){
            request(state,time,"model");
            jdbc.update("INSERT INTO request_details(request_id,started_at,member_id,upstream_mode,method,path,capture_state) VALUES(?,?,'member','subscription','POST','/v1/messages',?)",state,Timestamp.from(time),state);
            assertThat(query.detail(state).orElseThrow().get("capture_status")).as(state).isEqualTo("incomplete");
        }
        var page=controller.search(time.minusSeconds(1),time.plusSeconds(1),"","","","","",null,"incomplete",null,null,null,"","","","","","",25,"",new MockHttpServletResponse());
        assertThat(page.items()).hasSize(5).allSatisfy(row->assertThat(row.get("capture_status")).isEqualTo("incomplete"));
        request("provider-error",time,"model");
        jdbc.update("INSERT INTO request_details(request_id,started_at,member_id,upstream_mode,method,path,capture_state,upstream_headers_ms,error_type) VALUES('provider-error',?,'member','subscription','POST','/v1/messages','upstream_error',1,'')",Timestamp.from(time));
        assertThat(query.detail("provider-error").orElseThrow().get("capture_status")).isEqualTo("available");
        jdbc.update("UPDATE request_details SET upstream_headers_ms=NULL,error_type='IOException' WHERE request_id='provider-error'");
        assertThat(query.detail("provider-error").orElseThrow().get("capture_status")).isEqualTo("incomplete");
        jdbc.update("UPDATE request_details SET truncated=TRUE WHERE request_id='provider-error'");
        assertThat(query.detail("provider-error").orElseThrow().get("capture_status")).isEqualTo("truncated");
        jdbc.update("UPDATE request_details SET truncated=FALSE,capture_state='pending' WHERE request_id='provider-error'");
        assertThat(query.detail("provider-error").orElseThrow().get("capture_status")).isEqualTo("pending");
    }
}
