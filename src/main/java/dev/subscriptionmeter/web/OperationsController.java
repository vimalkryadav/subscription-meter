package dev.subscriptionmeter.web;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** AdminAuthFilter protects this read-only, bounded, allowlisted local report. */
@RestController
public class OperationsController {
    private final String path;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final Map<String,String> LABELS = Map.ofEntries(
        Map.entry("gateway","Gateway readiness"), Map.entry("prometheus","Gateway metrics collection"),
        Map.entry("worker_ready","Audit worker readiness"), Map.entry("worker_lag","Audit projection lag"),
        Map.entry("audit_guard","Audit admission guard"), Map.entry("upstream_auth","Upstream authentication responses"),
        Map.entry("disk","Workspace disk headroom"), Map.entry("backup","Latest backup"), Map.entry("restore","Latest restore rehearsal"));
    private static final Map<String,String> UNITS = Map.of("worker_lag","records","upstream_auth","responses / 10 min","disk","GiB free","backup","hours","restore","hours");
    @org.springframework.beans.factory.annotation.Autowired
    public OperationsController(@Value("${METER_OPERATIONS_REPORT:}") String path) { this(path, Clock.systemUTC()); }
    OperationsController(String path, Clock clock) { this.path=path; this.clock=clock; }
    public record Check(String id,String label,String state,Double value,String unit,int consecutiveFailures,boolean alerting) {}
    public record Report(String generatedAt,String status,boolean stale,List<Check> checks) {}
    @GetMapping("/admin/operations") public Report operations() {
        try {
            if(path.isBlank())return unavailable();
            byte[] bytes;
            try(var stream=Files.newInputStream(Path.of(path))) { bytes=stream.readNBytes(32769); }
            if(bytes.length>32768)return unavailable();
            var root=json.readTree(bytes);
            if(root.path("schemaVersion").asInt()!=1 || !root.path("checks").isArray() || root.path("checks").size()!=LABELS.size())return unavailable();
            Instant generated=Instant.parse(root.path("generatedAt").asText());
            long age=Duration.between(generated,clock.instant()).getSeconds();
            boolean stale=age>180 || age< -30;
            var checks=new ArrayList<Check>();var seen=new HashSet<String>();
            for(var item:root.path("checks")) {
                String id=item.path("id").asText(),state=item.path("state").asText();
                if(!LABELS.containsKey(id)||!seen.add(id)||!Set.of("ok","failed","unknown").contains(state))return unavailable();
                Double value=item.path("value").isNumber()?item.path("value").asDouble():null;
                if(value!=null&&(!Double.isFinite(value)||Math.abs(value)>1e15))return unavailable();
                int failures=item.path("consecutiveFailures").asInt();
                if(failures<0||failures>999)return unavailable();
                checks.add(new Check(id,LABELS.get(id),state,value,UNITS.getOrDefault(id,""),failures,failures>=3));
            }
            String status=stale?"unavailable":checks.stream().allMatch(c->c.state().equals("ok"))?"ok":checks.stream().anyMatch(Check::alerting)?"alert":"degraded";
            return new Report(generated.toString(),status,stale,List.copyOf(checks));
        } catch(Exception ignored) { return unavailable(); }
    }
    private Report unavailable() { return new Report(null,"unavailable",true,List.of()); }
}
