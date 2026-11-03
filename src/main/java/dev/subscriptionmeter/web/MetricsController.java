package dev.subscriptionmeter.web;

import dev.subscriptionmeter.domain.MetricsReader;
import java.time.Instant;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin")
public class MetricsController {
    private final MetricsReader reader;
    public MetricsController(MetricsReader reader) { this.reader=reader; }

    @GetMapping("/metrics") public MetricsReader.Report metrics(
            @RequestParam(defaultValue="6") int hours,@RequestParam(defaultValue="all") String mode) {
        if (!Set.of(1,6,24,168).contains(hours) || !Set.of("all","subscription").contains(mode))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported metrics window or mode");
        int step = switch(hours) { case 1 -> 60; case 6 -> 300; case 24 -> 900; default -> 3600; };
        Instant to=Instant.now();
        return reader.read(to.minusSeconds(hours*3600L),to,step,mode);
    }
}
