package dev.subscriptionmeter.upstream;

import java.io.IOException;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Reads only an explicitly provisioned file. Never discovers or copies local Claude login credentials. */
@Component
public class FileCredentialProvider implements UpstreamCredentialProvider {
    private final Path file;
    public FileCredentialProvider(@Value("${meter.mode}") String mode, @Value("${meter.upstream-token-file}") String file) {
        if (!mode.equals("subscription")) throw new IllegalArgumentException("Only subscription upstream mode is supported");
        this.file = Path.of(file);
    }
    public String bearerToken() throws IOException {
        String token = Files.readString(file).strip();
        if (token.isBlank() || token.contains("\n") || token.contains("\r")) throw new IOException("Credential missing or malformed");
        return token;
    }
}
