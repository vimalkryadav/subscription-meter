package dev.subscriptionmeter.portal;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class PortalTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private PortalTokens() {}
    static String generate() {
        byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String csrf(String session) { return hash("portal-csrf:"+session); }
    public static boolean equal(String a,String b) {
        return b!=null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));
    }
}
