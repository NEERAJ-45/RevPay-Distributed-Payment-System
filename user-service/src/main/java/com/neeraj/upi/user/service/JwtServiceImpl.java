package com.neeraj.upi.user.service;

import com.neeraj.upi.user.exception.InvalidJwtException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.*;

@Service
@Slf4j
public class JwtServiceImpl implements JwtService {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiry-ms}")
    private long expiryMs;

    // ── Token generation ──────────────────────────────────────────────────────

    /**
     * Generates a signed JWT with userId as subject, plus upiId and phone claims.
     * Uses JJWT 0.12.x — signWith(key) auto-selects HS256 for HMAC-SHA keys.
     */
    @Override
    public String generateToken(UUID userId, String upiId, String phone) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("upiId", upiId);
        claims.put("phone", phone);

        Date now    = new Date();
        Date expiry = new Date(now.getTime() + expiryMs);

        return Jwts.builder()
                .claims(claims)
                .subject(userId.toString())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(buildKey())       // JJWT 0.12.x — no deprecated SignatureAlgorithm
                .compact();
    }

    // ── Token validation / extraction ─────────────────────────────────────────

    /**
     * Parses and validates a JWT. Throws {@link InvalidJwtException} if the token
     * is malformed, has an invalid signature, or has expired.
     */
    @Override
    public Claims validateAndExtract(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(buildKey())          // JJWT 0.12.x
                    .build()
                    .parseSignedClaims(token)        // JJWT 0.12.x
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("JWT validation failed: {}", ex.getMessage());
            throw new InvalidJwtException("Token is invalid or expired: " + ex.getMessage());
        }
    }

    /** Returns the userId (subject) embedded in the token. */
    @Override
    public String extractUserId(String token) {
        return validateAndExtract(token).getSubject();
    }

    /** Returns the upiId claim embedded in the token. */
    @Override
    public String extractUpiId(String token) {
        return validateAndExtract(token).get("upiId", String.class);
    }

    /**
     * Returns {@code true} if the token parses and validates successfully.
     * Throws {@link InvalidJwtException} (a 401 BaseException) on any error —
     * callers that truly only need a boolean should catch it themselves.
     */
    @Override
    public boolean isTokenValid(String token) {
        validateAndExtract(token);   // throws InvalidJwtException on failure
        return true;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private SecretKey buildKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
