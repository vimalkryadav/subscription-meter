package dev.subscriptionmeter;

import dev.subscriptionmeter.upstream.FileCredentialProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class UpstreamConfigurationTest {
    @TempDir Path directory;
    @Test void retiredMockModeCannotSupplyFakeCredentials() {
        assertThatThrownBy(()->new FileCredentialProvider("mock",directory.resolve("token").toString()))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingOrBlankCredentialFailsInsteadOfFallingBack() throws Exception {
        Path file=directory.resolve("token");
        var credentials=new FileCredentialProvider("subscription",file.toString());
        assertThatThrownBy(credentials::bearerToken).isInstanceOf(IOException.class);
        Files.writeString(file,"\n");
        assertThatThrownBy(credentials::bearerToken).isInstanceOf(IOException.class);
        Files.writeString(file,"unit-test-credential\n");
        assertThat(credentials.bearerToken()).isEqualTo("unit-test-credential");
    }
}
