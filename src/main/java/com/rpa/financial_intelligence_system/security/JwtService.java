package com.rpa.financial_intelligence_system.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    private final SecretKey key; private final long hours;
    public JwtService(@Value("${app.jwt-secret}") String secret, @Value("${app.jwt-expiration-hours}") long hours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); this.hours = hours;
    }
    public String create(String username) {
        var now = Instant.now();
        return Jwts.builder().id(UUID.randomUUID().toString()).subject(username).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(hours * 3600))).signWith(key).compact();
    }
    public String parse(String token) { return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject(); }
    public long remainingSeconds(String token) { var exp=Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getExpiration(); return Math.max(1,(exp.getTime()-System.currentTimeMillis())/1000); }
}
