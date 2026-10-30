package dev.subscriptionmeter.upstream;
import java.io.IOException;

public interface UpstreamCredentialProvider { String bearerToken() throws IOException; }
