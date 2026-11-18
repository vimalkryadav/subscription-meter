package dev.subscriptionmeter.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts member gateway tokens so a member can view their current token again.
 * Authentication still uses only the SHA-256 hash; the sealed copy is for display.
 * The AES-256-GCM key is derived from the administrator secret, which never enters the database.
 */
@Component
public class MemberKeyVault {
    private static final String CONTEXT = "control-usage-member-key-v1:";
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    @Autowired
    public MemberKeyVault(@Value("${meter.admin-token-file}") Path adminFile) throws IOException {
        this(Files.readString(adminFile).strip(), true);
    }

    public static MemberKeyVault forSecret(String secret) { return new MemberKeyVault(secret, true); }

    private MemberKeyVault(String secret, boolean derived) {
        if (secret.length() < 32) throw new IllegalArgumentException("Secrets must be at least 32 characters");
        try {
            key = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest((CONTEXT + secret).getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private Cipher cipher(int mode, byte[] nonce, String memberId) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(memberId.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    public String seal(String memberId, String token) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            byte[] payload = cipher(Cipher.ENCRYPT_MODE, nonce, memberId).doFinal(token.getBytes(StandardCharsets.UTF_8));
            byte[] result = Arrays.copyOf(nonce, nonce.length + payload.length);
            System.arraycopy(payload, 0, result, nonce.length, payload.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Cannot protect member token", e); }
    }

    /** Empty when nothing was stored or the administrator secret has changed since sealing. */
    public Optional<String> open(String memberId, String sealed) {
        if (sealed == null || sealed.isBlank()) return Optional.empty();
        try {
            byte[] data = Base64.getDecoder().decode(sealed);
            if (data.length <= 12) return Optional.empty();
            return Optional.of(new String(cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(data, 12), memberId)
                    .doFinal(Arrays.copyOfRange(data, 12, data.length)), StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | IllegalArgumentException e) { return Optional.empty(); }
    }
}
