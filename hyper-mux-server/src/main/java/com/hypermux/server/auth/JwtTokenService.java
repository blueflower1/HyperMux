package com.hypermux.server.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** JWT 签发 / 解析（HS256）：payload 含 sub（userId）、uname、role、iat、exp（契约 §3.1）。 */
@Component
public class JwtTokenService {

    public record Issued(String token, long expiresAt) {
    }

    private final SecretKey key;
    private final JwtProperties props;

    public JwtTokenService(JwtProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public Issued issue(Long userId, String username, Integer role, Duration ttl) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("uname", username)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
        return new Issued(token, exp.toEpochMilli());
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public Duration ttlForRole(Integer role) {
        return role != null && role == AuthService.ROLE_ADMIN ? props.getAdminTtl() : props.getAppTtl();
    }
}
