package dev.subscriptionmeter.accounting;

import java.nio.file.*;
import java.time.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Reads only an operator-configured local numeric report; no credentials or public ingestion API. */
@Component
public class QuotaFileCollector {
    private final JdbcTemplate jdbc;
    private final String path;
    public QuotaFileCollector(JdbcTemplate jdbc,@Value("${meter.quota-file:}") String path){this.jdbc=jdbc;this.path=path;}
    @Scheduled(fixedDelay=60000,initialDelay=15000)
    public void collect() {
        if(path.isBlank())return;
        try {
            Path file=Path.of(path);
            if(!Files.isRegularFile(file)||Files.size(file)>16384)return;
            var root=JsonMapper.builder().build().readTree(Files.readString(file));
            String account=root.path("upstreamAccount").asString("");
            if(!account.matches("[a-zA-Z0-9_-]{1,80}"))return;
            Instant observed=Instant.parse(root.path("observedAt").asString());
            if(observed.isAfter(Instant.now().plusSeconds(30)) || observed.isBefore(Instant.now().minusSeconds(86400)))return;
            for(String bucket:List.of("five_hour","seven_day")) {
                var window=root.path("rate_limits").path(bucket);
                if(!window.path("used_percentage").isNumber() || !window.path("resets_at").isIntegralNumber() || !window.path("resets_at").canConvertToLong())continue;
                BigDecimal used=new BigDecimal(window.path("used_percentage").toString());
                Instant reset=Instant.ofEpochSecond(window.path("resets_at").asLong());
                if(used.signum()<0 || used.compareTo(BigDecimal.valueOf(100))>0 || Math.abs((long)used.scale())>12 || reset.isBefore(observed) || reset.isAfter(observed.plusSeconds(8L*86400)))continue;
                String id=UUID.nameUUIDFromBytes((account+":"+bucket+":"+observed+":"+reset+":"+used).getBytes(StandardCharsets.UTF_8)).toString();
                new QuotaSnapshots(jdbc).save(id,"anthropic",account,bucket,used,reset,observed,"coder_statusline");
            }
        } catch(RuntimeException|java.io.IOException e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Quota report unavailable ({})",e.getClass().getSimpleName());
        }
    }
}
