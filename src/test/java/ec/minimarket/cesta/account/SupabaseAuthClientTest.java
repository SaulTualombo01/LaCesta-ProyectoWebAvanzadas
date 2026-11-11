package ec.minimarket.cesta.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class SupabaseAuthClientTest {
    private static final URI PROJECT = URI.create("https://supabase.example.test");
    private static final String API_KEY = "sb_publishable_test";

    @Test
    void enviaCredencialesSoloPorElEndpointHttpsDeSupabase() {
        var json = new ObjectMapper();
        var builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
            assertThat(request.getURI()).isEqualTo(PROJECT.resolve("/auth/v1/token?grant_type=password"));
            assertThat(request.getMethod()).isEqualTo(HttpMethod.POST);
            assertThat(request.getHeaders().getFirst("apikey")).isEqualTo(API_KEY);
            assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
            var credentials = json.readTree(body);
            assertThat(credentials.get("email").asText()).isEqualTo("cliente@example.test");
            assertThat(credentials.get("password").asText()).isEqualTo("ClaveDePrueba321");
            return response("""
                    {"access_token":"token-de-prueba","refresh_token":"ignorado",
                     "user":{"id":"00000000-0000-4000-8000-000000000001","email":"cliente@example.test",
                             "email_confirmed_at":"2026-11-10T20:00:00Z","is_anonymous":false,
                             "user_metadata":{"name":"Ana"}}}
                    """);
        });
        var auth = new SupabaseAuth(builder, PROJECT, API_KEY);
        var session = auth.signIn("cliente@example.test", "ClaveDePrueba321");
        assertThat(session.user().id()).isEqualTo(UUID.fromString("00000000-0000-4000-8000-000000000001"));
        assertThat(session.user().confirmedAt()).isNotNull();
    }

    @Test
    void solicitaRecuperacionConPkceYUnDestinoFijo() {
        var json = new ObjectMapper();
        var builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
            assertThat(request.getURI().getPath()).isEqualTo("/auth/v1/recover");
            assertThat(request.getURI().getQuery()).isEqualTo("redirect_to=http://localhost:8080/restablecer");
            assertThat(request.getMethod()).isEqualTo(HttpMethod.POST);
            var payload = json.readTree(body);
            assertThat(payload.get("code_challenge_method").asText()).isEqualTo("s256");
            assertThat(payload.get("code_challenge").asText()).isEqualTo("challenge-de-prueba");
            assertThat(payload.has("password")).isFalse();
            return response("{}");
        });
        new SupabaseAuth(builder, PROJECT, API_KEY).recover("cliente@example.test", "challenge-de-prueba",
                "http://localhost:8080/restablecer");
    }

    private static MockClientHttpResponse response(String json) {
        var response = new MockClientHttpResponse(json.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response;
    }
}
