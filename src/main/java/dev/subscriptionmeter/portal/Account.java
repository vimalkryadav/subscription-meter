package dev.subscriptionmeter.portal;

public record Account(String id, String username, String displayName, String passwordHash) {
    public Profile profile() { return new Profile(id,username,displayName); }
    public record Profile(String id,String username,String displayName) {}
}
