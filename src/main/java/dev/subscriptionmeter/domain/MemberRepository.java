package dev.subscriptionmeter.domain;

import java.util.List;
import java.util.Optional;

public interface MemberRepository {
    void add(Member member, String keyHash, String keySealed);
    Optional<Member> findActiveByKeyHash(String keyHash);
    List<Member> list();
    boolean revoke(String id);
    Optional<Member> findById(String id);
    boolean replaceKey(String id, String keyHash, String keySealed, String keyPrefix, int rpm, int concurrency, boolean requireActive);
    Optional<String> sealedKey(String id);
}
