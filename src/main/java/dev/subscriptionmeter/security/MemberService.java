package dev.subscriptionmeter.security;

import dev.subscriptionmeter.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberService {
    private final MemberRepository members;
    private final SecureRandom random = new SecureRandom();
    public MemberService(MemberRepository members) { this.members = members; }
    public record IssuedKey(Member member, String key) {}
    public IssuedKey create(String name, int rpm, int concurrency) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String key = "sm_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Member m = new Member(UUID.randomUUID().toString(), name, key.substring(0, 10), true, rpm, concurrency);
        members.add(m, hash(key));
        return new IssuedKey(m, key);
    }
    public Member authenticate(String key) {
        return members.findActiveByKeyHash(hash(key)).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or revoked member key"));
    }
    public List<Member> list() { return members.list(); }
    public Member find(String id) {
        return members.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    public IssuedKey rotate(String id, int rpm, int concurrency, boolean requireActive) {
        Member old = find(id);
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String key = "sm_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if(!members.replaceKey(id,hash(key),key.substring(0,10),rpm,concurrency,requireActive))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Access was revoked");
        return new IssuedKey(new Member(id,old.name(),key.substring(0,10),true,rpm,concurrency),key);
    }
    public void revoke(String id) {
        if (!members.revoke(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
