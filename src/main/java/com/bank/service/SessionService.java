package com.bank.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stateless login tokens: "customerId.expiry" signed with HMAC-SHA256. Any server instance that has the
 * same secret (banking.session.secret) can verify a token, so logins survive restarts and work when
 * a host such as Vercel runs several instances.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    static final Duration LIFETIME = Duration.ofHours(2);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret;
    /** Logged-out tokens, kept until they expire. Per instance: on a multi-instance host a logged-out
     *  token can still work on another instance until it expires, which is why the lifetime is short. */
    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();

    public SessionService(@Value("${banking.session.secret:}") String configuredSecret) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
            log.warn("banking.session.secret (BANKING_SESSION_SECRET) is not set: using a random secret, so logins "
                    + "end when the server restarts. Set it on hosts that run several instances.");
        } else {
            if (configuredSecret.length() < 32) throw new IllegalStateException("BANKING_SESSION_SECRET must be at least 32 characters");
            this.secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    public String create(String customerId) {
        long expiry = Instant.now().plus(LIFETIME).getEpochSecond();
        String payload = B64.encodeToString((customerId + "." + expiry).getBytes(StandardCharsets.UTF_8));
        return payload + "." + B64.encodeToString(sign(payload));
    }

    public String requireCustomer(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) throw BankException.unauthorized("Please log in");
        String token = authHeader.substring(7).trim();
        int dot = token.indexOf('.');
        if (dot <= 0 || revoked.containsKey(token)) throw BankException.unauthorized("Please log in again");
        String payload = token.substring(0, dot);
        byte[] given;
        try {
            given = B64D.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw BankException.unauthorized("Please log in again");
        }
        if (!MessageDigest.isEqual(sign(payload), given)) throw BankException.unauthorized("Please log in again");

        String[] parts = new String(B64D.decode(payload), StandardCharsets.UTF_8).split("\\.");
        if (parts.length != 2) throw BankException.unauthorized("Please log in again");
        if (Instant.ofEpochSecond(Long.parseLong(parts[1])).isBefore(Instant.now())) {
            throw BankException.unauthorized("Session expired, please log in again");
        }
        return parts[0];
    }

    public void invalidate(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return;
        Instant now = Instant.now();
        revoked.values().removeIf(exp -> exp.isBefore(now));
        revoked.put(authHeader.substring(7).trim(), now.plus(LIFETIME));
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
