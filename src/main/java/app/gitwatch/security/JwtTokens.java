package app.gitwatch.security;

import app.gitwatch.api.ApiCodes;
import app.gitwatch.api.ApiException;
import app.gitwatch.config.AppProperties;
import app.gitwatch.model.User;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class JwtTokens {

    public static final String ACCESS = "access";
    public static final String STATE = "oauth_state";
    public static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final SecretKey key;
    private final Duration accessTtl;
    private final Clock clock;

    public JwtTokens(AppProperties properties, Clock clock) {
        this.key = key(properties.getJwtSecret());
        Duration expiry = properties.getJwtExpiry();
        this.accessTtl = expiry == null || expiry.isZero() || expiry.isNegative() ? Duration.ofDays(7) : expiry;
        this.clock = clock;
    }

    public Duration accessTtl() {
        return accessTtl;
    }

    public SecretKey key() {
        return key;
    }

    public String issueAccess(User user) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("typ", ACCESS)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(accessTtl)))
                .build();
        return sign(claims);
    }

    public String issueState(String redirectUri) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("typ", STATE)
                .claim("redir", redirectUri)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(STATE_TTL)))
                .build();
        return sign(claims);
    }

    public String requireStateRedirect(String state) {
        SignedJWT jwt = parse(state);
        String redirect = claim(jwt, "redir");
        if (!STATE.equals(claim(jwt, "typ")) || redirect == null || redirect.isBlank()) {
            throw invalidState();
        }
        return redirect;
    }

    public static SecretKey key(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("GITWATCH_JWT_SECRET is required");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("GITWATCH_JWT_SECRET must be at least 32 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    private String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(key.getEncoded()));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException("failed to sign token", ex);
        }
    }

    private SignedJWT parse(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(key.getEncoded()))) {
                throw invalidState();
            }
            Date expires = jwt.getJWTClaimsSet().getExpirationTime();
            if (expires == null || !expires.toInstant().isAfter(clock.instant())) {
                throw invalidState();
            }
            return jwt;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalidState();
        }
    }

    private static String claim(SignedJWT jwt, String name) {
        try {
            return jwt.getJWTClaimsSet().getStringClaim(name);
        } catch (Exception ex) {
            throw invalidState();
        }
    }

    private static ApiException invalidState() {
        return new ApiException(400, ApiCodes.VALIDATION_ERROR, "OAuth state is invalid");
    }
}
