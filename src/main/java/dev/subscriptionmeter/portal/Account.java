package dev.subscriptionmeter.portal;

public record Account(String id, String username, String displayName, String passwordHash, String role) {
    public Profile profile() { return new Profile(id,username,displayName,role); }
    public record Profile(String id,String username,String displayName,String role) {}
}
