package dev.subscriptionmeter.domain;

import java.util.List;
import java.util.Optional;

public interface MemberRepository {
    void add(Member member, String keyHash);
    Optional<Member> findActiveByKeyHash(String keyHash);
    List<Member> list();
    boolean revoke(String id);
}
