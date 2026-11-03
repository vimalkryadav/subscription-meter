package dev.subscriptionmeter.portal;

import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class Pbkdf2PasswordHasher implements PasswordHasher {
    private final Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder("",16,600000,
            Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
    public String encode(String password) { return encoder.encode(password); }
    public boolean matches(String password,String encoded) { return encoder.matches(password,encoded); }
}
