package ec.minimarket.cesta.account;

import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class EmailFlow {
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{8,256}");
    private final URI publicOrigin;

    EmailFlow(@Value("${APP_PUBLIC_URL:https://localhost:8080}") URI origin) {
        boolean loopback = origin.getHost() != null
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(origin.getHost());
        boolean validScheme = "https".equalsIgnoreCase(origin.getScheme())
                || (loopback && "http".equalsIgnoreCase(origin.getScheme()));
        if (!validScheme || origin.getHost() == null || origin.getUserInfo() != null
                || origin.getQuery() != null || origin.getFragment() != null
                || !(origin.getPath().isEmpty() || "/".equals(origin.getPath()))) {
            throw new IllegalArgumentException("APP_PUBLIC_URL debe ser un origen HTTPS o localhost para pruebas.");
        }
        this.publicOrigin = origin;
    }

    Pending start(HttpSession session, String path, String email) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        var pending = new Pending(email, BASE64.encodeToString(random), Instant.now().plus(20, ChronoUnit.MINUTES));
        session.setAttribute(key(path), pending);
        return pending;
    }

    Pending read(HttpSession session, String path, String code) {
        if (code == null || !CODE.matcher(code).matches()) return null;
        return session.getAttribute(key(path)) instanceof Pending pending && pending.expiry().isAfter(Instant.now())
                ? pending : null;
    }

    void clear(HttpSession session, String path) {
        session.removeAttribute(key(path));
    }

    String redirect(String path) {
        return publicOrigin.resolve(path).toString();
    }

    private static String key(String path) {
        return EmailFlow.class.getName() + path;
    }

    record Pending(String email, String verifier, Instant expiry) {
        String challenge() {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
                return BASE64.encodeToString(digest);
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 no está disponible.", impossible);
            }
        }

        boolean matches(SupabaseAuth.AuthSession session) {
            if (session == null || session.accessToken() == null || session.accessToken().isBlank()) return false;
            var user = session.user();
            return user != null && user.id() != null && !user.anonymous() && user.confirmedAt() != null
                    && user.email() != null && email.equals(AccountAuthentication.normalize(user.email()));
        }
    }
}
