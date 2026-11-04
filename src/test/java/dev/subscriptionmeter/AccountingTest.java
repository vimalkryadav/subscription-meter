package dev.subscriptionmeter;

import dev.subscriptionmeter.accounting.*;
import dev.subscriptionmeter.usage.AnthropicUsageObserver;
import java.math.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class AccountingTest {
    JdbcTemplate jdbc;AccountingService service;
    Instant from=Instant.parse("2026-11-04T00:00:00Z"),to=from.plusSeconds(3600);
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:account"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),new ClassPathResource("accounting-schema.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);service=new AccountingService(jdbc);
        jdbc.update("INSERT INTO members(id,name,key_hash,key_prefix,rpm,concurrency_limit) VALUES('alice','Alice','hash-a','sm_a',60,4),('bob','Bob','hash-b','sm_b',60,4)");
        jdbc.update("INSERT INTO accounting_price_versions(id,provider,model,valid_from,currency,rates_json,source_url) VALUES('price-v1','anthropic','claude-opus-5-5',?,'USD',?,?)",Timestamp.from(from),"{\"input\":\"4\",\"output\":\"20\",\"cache_read\":\"0.2\",\"cache_write_5m\":\"5\",\"cache_write_1h\":\"8\"}","https://platform.claude.com/docs/en/about-claude/pricing");
    }
    void request(String id,String member,String components,boolean complete){
        jdbc.update("INSERT INTO requests(id,member_id,started_at,path,model,state,status_code,usage_components,usage_complete,usage_seen,input_tokens,output_tokens) VALUES(?,?,?,'/v1/messages','claude-opus-5-5','complete',200,?,?,TRUE,100,20)",id,member,Timestamp.from(from.plusSeconds(1)),components,complete);
        jdbc.update("INSERT INTO request_details(request_id,started_at,member_id,upstream_mode,method,path,request_body,capture_state) VALUES(?,?,?,'subscription','POST','/v1/messages',?,'complete')",id,Timestamp.from(from.plusSeconds(1)),member,"{\"messages\":[{\"role\":\"user\",\"content\":\"debug this crash\"}]}");
    }
    @Test void missingFieldsRemainUnknownAndCacheBreakdownIsNotCountedTwice(){
        var calc=new PriceCalculator();var rates=Map.of("input",new BigDecimal("4"),"output",new BigDecimal("20"),"cache_read",new BigDecimal("0.2"),"cache_write_5m",new BigDecimal("5"),"cache_write_1h",new BigDecimal("8"));
        var values=Map.of("input",100L,"output",20L,"cache_read",50L,"cache_write",30L,"cache_write_5m",10L,"cache_write_1h",20L);
        var exact=calc.estimate(values,true,rates,"v1");
        assertThat(exact.status()).isEqualTo("complete");assertThat(exact.knownSubtotalUsd()).isEqualByComparingTo("0.00102");
        var unknown=calc.estimate(Map.of("input",100L,"output",20L,"cache_write",30L),true,rates,"v1");
        assertThat(unknown.status()).isEqualTo("partial");assertThat(unknown.missingComponents()).contains("cache_write_ttl","cache_read");
        assertThat(calc.estimate(Map.of(),false,rates,"v1").knownSubtotalUsd()).isNull();
    }
    @Test void observerDistinguishesMissingFromZeroAndPreservesTtl(){
        var observer=new AnthropicUsageObserver(false);
        byte[] bytes="{\"usage\":{\"input_tokens\":0,\"output_tokens\":2,\"cache_creation_input_tokens\":5,\"cache_creation\":{\"ephemeral_5m_input_tokens\":5,\"ephemeral_1h_input_tokens\":0}}}".getBytes();
        observer.accept(bytes,bytes.length);observer.finishJson();var usage=observer.snapshot();
        assertThat(usage.components()).containsEntry("input",0L).containsEntry("cache_write_1h",0L).doesNotContainKey("cache_read");
        assertThat(usage.complete()).isTrue();
    }
    @Test void projectionAndManualOverrideAreIdempotentAndAudited(){
        request("r1","alice","{\"input\":100,\"output\":20,\"cache_read\":0,\"cache_write\":0}",true);
        service.project("r1");service.project("r1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_assessments",Integer.class)).isEqualTo(1);
        service.classify("r1","testing","owner");service.project("r1");
        assertThat(service.detail("r1").get("classification").toString()).contains("testing","admin_override");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log",Integer.class)).isEqualTo(1);
        var report=service.report(from,to,"","anthropic");
        assertThat(report.get("summary").toString()).contains("requestCount=1","pricedRequests=1");
        assertThat(report.get("quota").toString()).contains("unavailable");
        jdbc.update("UPDATE accounting_price_versions SET rates_json=? WHERE id='price-v1'","{\"input\":\"999\"}");
        assertThat(service.detail("r1").get("estimate").toString()).contains("0.0008000000");
    }
    @Test void invoicesReconcileAndZeroActivityStaysUnallocated(){
        var invoices=new SubscriptionAccounting(jdbc,service);
        var data=new SubscriptionAccounting.Invoice("anthropic","primary",from,to,new BigDecimal("100.00"),"USD",new BigDecimal("0.5"),"reported_tokens","manual_invoice");
        String id=invoices.add(data,"owner");
        assertThat((BigDecimal)invoices.allocation(id).get("unallocatedAmount")).isEqualByComparingTo("100");
        request("r1","alice","{}",false);request("r2","bob","{}",false);
        var allocation=invoices.allocation(id);
        assertThat((BigDecimal)allocation.get("allocatedAmount")).isEqualByComparingTo("50");
        assertThat((BigDecimal)allocation.get("unallocatedAmount")).isEqualByComparingTo("50");
        assertThat(allocation.get("usageCoverage")).isEqualTo("partial");
    }
    @Test void classificationUsesLastInstructionAndRetainsUnknown(){
        var c=new WorkClassifier();
        assertThat(c.classify("{\"messages\":[{\"role\":\"user\",\"content\":\"deploy a server\"},{\"role\":\"user\",\"content\":\"debug this crash\"}]}").category()).isEqualTo("debugging");
        assertThat(c.classify("incomplete json").category()).isEqualTo("unknown");
        assertThat(c.classify("{\"messages\":[{\"role\":\"user\",\"content\":\"create tests\"}]}").category()).isEqualTo("mixed");
    }
    @Test void lateCaptureAndUsageRefreshProjectionWithoutLosingManualOverrideOrPriceVersion(){
        request("late","alice","{\"input\":100}",false);
        jdbc.update("DELETE FROM request_details WHERE request_id='late'");
        assertThat(service.projectPending()).isEqualTo(1);
        service.classify("late","testing","owner");
        jdbc.update("INSERT INTO request_details(request_id,started_at,member_id,upstream_mode,method,path,request_body,capture_state) VALUES('late',?,'alice','subscription','POST','/v1/messages',?,'complete')",Timestamp.from(from.plusSeconds(1)),"{\"messages\":[{\"role\":\"user\",\"content\":\"debug this crash\"}]}");
        jdbc.update("UPDATE requests SET usage_components=?,usage_complete=TRUE WHERE id='late'","{\"input\":100,\"output\":20,\"cache_read\":0,\"cache_write\":0}");
        jdbc.update("INSERT INTO accounting_price_versions(id,provider,model,valid_from,currency,rates_json,source_url) VALUES('new-price','anthropic','claude-opus-5-5',?,'USD',?,'https://example.invalid/prices')",Timestamp.from(from.plusMillis(1)),"{\"input\":\"999\",\"output\":\"999\"}");
        assertThat(service.projectPending()).isEqualTo(1);
        var detail=service.detail("late");
        assertThat(detail.get("classification").toString()).contains("category=testing","suggestedCategory=debugging");
        assertThat(detail.get("estimate").toString()).contains("price-v1","0.0008000000","status=complete");
        assertThat(service.projectPending()).isZero();
        jdbc.update("DELETE FROM request_details WHERE request_id='late'");
        service.classify("late","review","owner");
        assertThat(service.detail("late").get("estimate").toString()).contains("0.0008000000");
        assertThat(service.projectPending()).isZero();
    }
    @Test void partialHistoricalUsageCanBeCompletedByLaterCapturedEvents(){
        request("history","alice",null,false);
        jdbc.update("UPDATE request_details SET capture_state='pending',event_count=1 WHERE request_id='history'");
        event("history",1,"message_start","{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":0,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}}");
        service.projectPending();
        assertThat(jdbc.queryForObject("SELECT usage_components FROM requests WHERE id='history'",String.class)).isNull();
        assertThat(service.detail("history").get("usageComponents").toString()).contains("input=10");
        event("history",2,"message_delta","{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":20}}");
        event("history",3,"message_stop","{\"type\":\"message_stop\"}");
        jdbc.update("UPDATE request_details SET capture_state='complete',event_count=3 WHERE request_id='history'");
        assertThat(service.projectPending()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT usage_complete FROM requests WHERE id='history'",Boolean.class)).isTrue();
        assertThat(service.detail("history").get("estimate").toString()).contains("status=complete");
        assertThat(service.projectPending()).isZero();
    }
    private void event(String id,int sequence,String type,String data){
        jdbc.update("INSERT INTO request_events(request_id,request_started_at,observed_at,sequence,elapsed_ms,event_type,event_data) VALUES(?,?,?,?,1,?,?)",id,Timestamp.from(from.plusSeconds(1)),Timestamp.from(from.plusSeconds(sequence)),sequence,type,data);
    }
    @Test void invoiceRoundingNeverAllocatesToZeroWeightAndRejectsFractionalCents(){
        request("a","alice","{}",false);request("b","bob","{}",false);
        jdbc.update("INSERT INTO members(id,name,key_hash,key_prefix,rpm,concurrency_limit) VALUES('zero','Zero','hash-z','sm_z',60,4)");
        request("z","zero","{}",false);jdbc.update("UPDATE requests SET input_tokens=0,output_tokens=0 WHERE id='z'");
        var invoices=new SubscriptionAccounting(jdbc,service);
        String id=invoices.add(new SubscriptionAccounting.Invoice("anthropic","primary",from,to,new BigDecimal("0.01"),"USD",BigDecimal.ONE,"reported_tokens","manual_invoice"),"owner");
        var allocation=invoices.allocation(id);
        @SuppressWarnings("unchecked") var members=(List<Map<String,Object>>)allocation.get("members");
        assertThat((BigDecimal)members.stream().filter(m->m.get("memberId").equals("zero")).findFirst().orElseThrow().get("allocatedAmount")).isEqualByComparingTo("0");
        assertThat(members.stream().map(m->(BigDecimal)m.get("allocatedAmount")).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("0.01");
        assertThat((BigDecimal)allocation.get("unallocatedAmount")).isEqualByComparingTo("0");
        assertThatThrownBy(()->invoices.add(new SubscriptionAccounting.Invoice("anthropic","primary",from,to,new BigDecimal("0.005"),"USD",BigDecimal.ONE,"equal","manual_invoice"),"owner")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void negativeCountersAndRatesNeverProduceCompleteOrNegativeValue(){
        var calc=new PriceCalculator();
        var estimate=calc.estimate(Map.of("input",10L,"output",0L,"cache_read",0L,"cache_write",-1L),true,Map.of("input",BigDecimal.ONE,"output",BigDecimal.ZERO),"price");
        assertThat(estimate.status()).isEqualTo("partial");assertThat(estimate.missingComponents()).contains("invalid_cache_write");
        var invalid=calc.estimate(Map.of("input",10L,"output",0L,"cache_read",0L,"cache_write",0L),true,Map.of("input",new BigDecimal("-1"),"output",BigDecimal.ZERO),"price");
        assertThat(invalid.missingComponents()).contains("invalid_rate_input");assertThat(invalid.knownSubtotalUsd()).isEqualByComparingTo("0");
    }
    @Test void quotaDeduplicationAndPerWindowFreshnessAreExplicit() throws Exception {
        Instant now=Instant.now();var quotas=new QuotaSnapshots(jdbc);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)){
            var jobs=new ArrayList<java.util.concurrent.Callable<Void>>();
            for(int n=0;n<8;n++)jobs.add(()->{quotas.recordHeaders("anthropic","primary","{\"anthropic-ratelimit-unified-5h-utilization\":[\"0.03\"],\"anthropic-ratelimit-unified-5h-reset\":[\""+now.plusSeconds(3600).getEpochSecond()+"\"]}",now);return null;});
            for(var future:pool.invokeAll(jobs))future.get();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_quota_snapshots",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT used_percentage FROM provider_quota_snapshots",BigDecimal.class)).isEqualByComparingTo("3");
        quotas.save("stale","anthropic","primary","seven_day",new BigDecimal("95"),now.plusSeconds(86400),now.minusSeconds(600),"coder_statusline");
        var report=service.quota("anthropic");assertThat(report.get("state")).isEqualTo("stale");
        assertThat(report.get("snapshots").toString()).contains("state=available","state=stale","source=upstream_headers_v1");
    }
}
