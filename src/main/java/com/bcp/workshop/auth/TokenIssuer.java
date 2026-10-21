package com.bcp.workshop.auth;

@FunctionalInterface
public interface TokenIssuer {
    String issue(String username, String role, String client);
}