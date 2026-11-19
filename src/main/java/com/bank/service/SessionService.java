package com.bank.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory login sessions: opaque random token -> customerId, expiring after 30 minutes idle. */
@Service
public class SessionService {

    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private record Session(String customerId, Instant lastSeen) {}

    public String create(String customerId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new Session(customerId, Instant.now()));
        return token;
    }

    public String requireCustomer(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw BankException.unauthorized("Please log in");
        }
        String token = authHeader.substring(7);
        Session s = sessions.get(token);
        if (s == null || s.lastSeen().plus(IDLE_TIMEOUT).isBefore(Instant.now())) {
            sessions.remove(token);
            throw BankException.unauthorized("Session expired, please log in again");
        }
        sessions.put(token, new Session(s.customerId(), Instant.now()));
        return s.customerId();
    }

    public void invalidate(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) sessions.remove(authHeader.substring(7));
    }
}
