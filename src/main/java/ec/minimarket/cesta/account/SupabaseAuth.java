package ec.minimarket.cesta.account;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class SupabaseAuth {
    private static final Logger log = LoggerFactory.getLogger(SupabaseAuth.class);
    private final RestClient http;

    SupabaseAuth(RestClient.Builder builder, @Value("${SUPABASE_URL}") URI origin,
                 @Value("${SUPABASE_PUBLISHABLE_KEY}") String key) {
        if (!"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null
                || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
                || !(origin.getPath().isEmpty() || "/".equals(origin.getPath()))) {
            throw new IllegalArgumentException("SUPABASE_URL debe ser el origen HTTPS del proyecto.");
        }
        if (!key.matches("sb_publishable_[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Configura la clave publishable de Supabase.");
        }
        var transport = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        transport.setReadTimeout(Duration.ofSeconds(10));
        http = builder.requestFactory(transport)
                .baseUrl(origin.resolve("/auth/v1").toString())
                .defaultHeader("apikey", key)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public AuthSession signIn(String email, String password) {
        return request(() -> http.post().uri("/token?grant_type=password")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("email", email, "password", password))
                .retrieve().body(AuthSession.class));
    }

    public void register(String name, String email, String password, String challenge, String redirect) {
        AuthSession response = request(() -> http.post()
                .uri(builder -> builder.path("/signup").queryParam("redirect_to", redirect).build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password, "data", Map.of("name", name),
                        "code_challenge", challenge, "code_challenge_method", "s256"))
                .retrieve().body(AuthSession.class));
        revoke(response, "local");
    }

    public void recover(String email, String challenge, String redirect) {
        request(() -> http.post()
                .uri(builder -> builder.path("/recover").queryParam("redirect_to", redirect).build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "code_challenge", challenge, "code_challenge_method", "s256"))
                .retrieve().toBodilessEntity());
    }

    public AuthSession exchange(String code, String verifier) {
        return request(() -> http.post().uri("/token?grant_type=pkce").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("auth_code", code, "code_verifier", verifier)).retrieve().body(AuthSession.class));
    }

    public void updatePassword(AuthSession session, String password) {
        request(() -> http.put().uri("/user").headers(headers -> headers.setBearerAuth(session.accessToken()))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("password", password))
                .retrieve().toBodilessEntity());
    }

    public void revoke(AuthSession session, String scope) {
        if (session == null || session.accessToken() == null || session.accessToken().isBlank()) return;
        try {
            request(() -> http.post().uri("/logout?scope={scope}", scope)
                    .headers(headers -> headers.setBearerAuth(session.accessToken())).retrieve().toBodilessEntity());
        } catch (Rejected | Unavailable failure) {
            log.warn("No se pudo revocar una sesión temporal de Supabase Auth.");
        }
    }

    private static <T> T request(Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException response) {
            int status = response.getStatusCode().value();
            if (status >= 400 && status < 500 && status != 429) throw new Rejected();
            throw new Unavailable();
        } catch (RestClientException failure) {
            throw new Unavailable();
        }
    }

    public record AuthSession(@JsonProperty("access_token") String accessToken, Identity user) {}

    public record Identity(UUID id, String email, @JsonProperty("email_confirmed_at") Instant confirmedAt,
                           @JsonProperty("is_anonymous") boolean anonymous,
                           @JsonProperty("user_metadata") Map<String, Object> metadata) {}

    public static final class Rejected extends RuntimeException {}
    public static final class Unavailable extends RuntimeException {}
}
