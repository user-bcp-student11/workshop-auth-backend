package com.bcp.workshop.auth;

record UserCredentials(UserRecord user, String passwordHash) {}