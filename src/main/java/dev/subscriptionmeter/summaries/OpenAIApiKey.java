package dev.subscriptionmeter.summaries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The OpenAI API key used only for administrator-side work (session summaries, and later the
 * last-resort fallback). Re-read on each use so the operator can add or replace it without a restart.
 * An empty or missing file means "not configured"; the value is never logged or returned by any API.
 */
@Component
public class OpenAIApiKey {
    private static final int MAX_BYTES = 1024;
    private static final Pattern SHAPE = Pattern.compile("sk-[A-Za-z0-9_-]{20,500}");
    private final Path path;

    public OpenAIApiKey(@Value("${meter.openai-api.key-file:}") String path) {
        this.path = path == null || path.isBlank() ? null : Path.of(path);
    }

    public Optional<String> read() {
        if (path == null || !Files.isRegularFile(path)) return Optional.empty();
        byte[] bytes;
        try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        catch (IOException e) { return Optional.empty(); }
        if (bytes.length > MAX_BYTES) return Optional.empty();
        String key = new String(bytes, StandardCharsets.UTF_8).strip();
        return SHAPE.matcher(key).matches() ? Optional.of(key) : Optional.empty();
    }

    public boolean configured() { return read().isPresent(); }
}
