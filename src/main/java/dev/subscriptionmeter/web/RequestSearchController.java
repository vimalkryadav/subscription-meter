package dev.subscriptionmeter.web;

import dev.subscriptionmeter.domain.RequestQuery;
import dev.subscriptionmeter.domain.RequestQuery.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** All routes are protected by AdminAuthFilter; cursors contain metadata only. */
@RestController
@RequestMapping("/admin")
public class RequestSearchController {
    private final RequestQuery query;
    private final JsonMapper json=JsonMapper.builder().build();
    public RequestSearchController(RequestQuery query){this.query=query;}

    @GetMapping("/request-search") public Page search(
            @RequestParam(required=false) Instant from, @RequestParam(required=false) Instant to,
            @RequestParam(defaultValue="") String memberId,@RequestParam(defaultValue="") String model,
            @RequestParam(defaultValue="") String requestId,@RequestParam(defaultValue="") String upstreamRequestId,
            @RequestParam(defaultValue="") String state,@RequestParam(required=false) Integer status,
            @RequestParam(defaultValue="") String captureState,@RequestParam(required=false) Long minDurationMs,
            @RequestParam(required=false) Long maxDurationMs,@RequestParam(required=false) Boolean usageSeen,
            @RequestParam(defaultValue="") String provider,@RequestParam(defaultValue="") String upstreamAccount,
            @RequestParam(defaultValue="") String clientFamily,@RequestParam(defaultValue="") String keyPrefix,
            @RequestParam(defaultValue="") String category,
            @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="") String cursor,
            HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        Instant now=Instant.now(),asOf=now; Position position=null;
        String[] parts=null;
        if(!cursor.isEmpty()){
            try{if(cursor.length()>2048)throw new IllegalArgumentException();
                parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
                if(parts.length!=6||!parts[0].equals("1"))throw new IllegalArgumentException();
                asOf=Instant.parse(parts[1]);position=new Position(Instant.parse(parts[2]),parts[3]);
                if(from==null)from=Instant.parse(parts[4]);
            }catch(RuntimeException e){throw bad("Invalid cursor");}
        }
        if(to==null||to.isAfter(asOf))to=asOf;
        if(from==null)from=to.minus(1,ChronoUnit.DAYS);
        if(!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(365))>0||limit<1||limit>100)
            throw bad("Invalid time window or page size");
        for(String value:List.of(memberId,model,requestId,upstreamRequestId,state,captureState,provider,upstreamAccount,clientFamily,keyPrefix,category))
            if(value.length()>200||value.chars().anyMatch(c->Character.isISOControl(c)))throw bad("Invalid filter");
        if(status!=null&&(status<100||status>599))throw bad("Invalid HTTP status");
        if(minDurationMs!=null&&minDurationMs<0||maxDurationMs!=null&&maxDurationMs<0
                ||minDurationMs!=null&&maxDurationMs!=null&&minDurationMs>maxDurationMs)throw bad("Invalid duration");
        if(!Set.of("","pending","available","incomplete","truncated","error","expired","unavailable").contains(captureState))throw bad("Invalid capture state");
        if(!Set.of("","anthropic","openai").contains(provider))throw bad("Invalid provider");
        Filter filter=new Filter(from,to,memberId,model,requestId,upstreamRequestId,state,status,captureState,minDurationMs,maxDurationMs,usageSeen,provider,upstreamAccount,clientFamily,keyPrefix,category);
        String fingerprint=fingerprint(filter);
        if(parts!=null&&(!parts[5].equals(fingerprint)||position.startedAt().isBefore(from)
                ||!position.startedAt().isBefore(to)||position.id().length()>36))throw bad("Cursor does not match filters");
        var rows=query.search(filter,position,limit+1);
        String next=null;
        if(rows.size()>limit){rows=new ArrayList<>(rows.subList(0,limit));var last=rows.getLast();
            next=Base64.getUrlEncoder().withoutPadding().encodeToString(("1|"+asOf+"|"+instant(last.get("started_at"))
                    +"|"+last.get("id")+"|"+from+"|"+fingerprint).getBytes(StandardCharsets.UTF_8));}
        return new Page(rows,next,asOf,from,to,now);
    }

    @GetMapping("/request-search/{id}") public Map<String,Object> detail(@PathVariable String id,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");return query.detail(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Page events directly to the response; never accumulate the entire export in browser/server memory. */
    @GetMapping("/requests/{id}/export") public void export(@PathVariable String id,HttpServletResponse response) throws IOException {
        if(!id.matches("[A-Za-z0-9_-]{1,36}"))throw bad("Invalid request ID");
        var detail=query.detail(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        int through=((Number)Objects.requireNonNullElse(detail.get("event_count"),0)).intValue();
        Instant startedAt=instant(Objects.requireNonNullElse(detail.get("capture_started_at"),detail.get("started_at")));
        response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("Content-Disposition","attachment; filename=\"request-"+id+".json\"");
        var out=response.getWriter();
        out.write("{\"manifest\":");out.write(json.writeValueAsString(Map.of("exportedAt",Instant.now().toString(),
                "captureStatus",detail.get("capture_status"),"eventSequenceLimit",through,
                "notice","Redacted retained capture. Pending, incomplete, truncated, error, unavailable or expired captures do not establish a complete response.")));
        out.write(",\"request\":");out.write(json.writeValueAsString(detail));out.write(",\"events\":[");
        int after=0;boolean first=true;
        while(after<through){var page=query.events(id,startedAt,after,through,100);if(page.isEmpty())break;
            for(var event:page){if(!first)out.write(",");out.write(json.writeValueAsString(event));first=false;}
            int next=((Number)page.getLast().get("sequence")).intValue();if(next<=after)break;after=next;out.flush();}
        out.write("],\"exportedThroughSequence\":"+after+"}");out.flush();
    }
    private static String fingerprint(Filter filter){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(filter.toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public static Instant instant(Object value){
        if(value instanceof java.sql.Timestamp t)return t.toInstant();
        if(value instanceof OffsetDateTime t)return t.toInstant();
        if(value instanceof Instant t)return t;
        return Instant.parse(value.toString());
    }
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
