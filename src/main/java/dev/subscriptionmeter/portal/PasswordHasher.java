package dev.subscriptionmeter.portal;

public interface PasswordHasher {
    String encode(String password);
    boolean matches(String password,String encoded);
}
