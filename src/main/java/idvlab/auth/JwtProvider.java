package idvlab.auth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * 액세스 토큰 발급·검증 (실습 범위: 리프레시 토큰 없음).
 * jti(토큰 고유 ID)를 넣어, 같은 회원에게 같은 초에 발급해도 토큰이 서로 달라지게 한다.
 */
@Component
public class JwtProvider {

    private final SecretKey key;
    private final JwtProperties properties;

    public JwtProvider(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(Long memberId) {
        Date now = new Date();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(memberId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + properties.accessTokenExpiry().toMillis()))
                .signWith(key)
                .compact();
    }

    /** 서명·만료가 유효하면 회원 ID, 아니면 empty */
    public Optional<Long> parseMemberId(String token) {
        try {
            String sub = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject();
            return Optional.of(Long.valueOf(sub));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
