package ec.minimarket.cesta.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupabaseAuthClientTest {
    private static final URI PROJECT = URI.create("https://supabase.example.test");
    private static final String API_KEY = "sb_publishable_test";
    private static final UUID USER_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String TOKEN = "token-de-prueba";

    @Test
    void enviaCredencialesSoloPorElEndpointHttpsDeSupabase() {
        var json = new ObjectMapper();
        var requests = new ArrayList<String>();
        var builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
            requests.add(request.getURI().getPath());
            assertThat(request.getHeaders().getFirst("apikey")).isEqualTo(API_KEY);
            if (request.getMethod() == HttpMethod.GET) {
                assertThat(request.getURI()).isEqualTo(PROJECT.resolve("/auth/v1/user"));
                assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + TOKEN);
                return response("""
                        {"id":"00000000-0000-4000-8000-000000000001","email":"cliente@example.test",
                         "email_confirmed_at":"2026-11-10T20:00:00Z","is_anonymous":false,
                         "user_metadata":{"name":"Ana verificada"}}
                        """);
            }
            assertThat(request.getURI()).isEqualTo(PROJECT.resolve("/auth/v1/token?grant_type=password"));
            assertThat(request.getMethod()).isEqualTo(HttpMethod.POST);
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
        assertThat(session.user().id()).isEqualTo(USER_ID);
        assertThat(session.user().confirmedAt()).isNotNull();
        assertThat(session.user().metadata()).containsEntry("name", "Ana verificada");
        assertThat(requests).containsExactly("/auth/v1/token", "/auth/v1/user");
    }

    @Test
    void rechazaUnInicioDeSesionSiSupabaseRechazaElJwt() {
        var requests = new ArrayList<String>();
        var auth = authWithUserResponse(requests,
                new MockClientHttpResponse(new byte[0], HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> auth.signIn("cliente@example.test", "ClaveDePrueba321"))
                .isInstanceOf(SupabaseAuth.Rejected.class);
        assertThat(requests).containsExactly("/auth/v1/token", "/auth/v1/user", "/auth/v1/logout");
    }

    @Test
    void rechazaUnEnlacePkceSiLaIdentidadDelJwtNoCoincide() {
        var requests = new ArrayList<String>();
        var auth = authWithUserResponse(requests,
                response("""
                        {"id":"00000000-0000-4000-8000-000000000002"}
                        """));

        assertThatThrownBy(() -> auth.exchange("codigo-de-prueba", "verificador-de-prueba"))
                .isInstanceOf(SupabaseAuth.Rejected.class);
        assertThat(requests).containsExactly("/auth/v1/token", "/auth/v1/user", "/auth/v1/logout");
    }

    private static SupabaseAuth authWithUserResponse(ArrayList<String> requests, MockClientHttpResponse user) {
        var builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
            String path = request.getURI().getPath();
            requests.add(path);
            return switch (path) {
                case "/auth/v1/token" -> response("""
                        {"access_token":"token-de-prueba", "user":{"id":"00000000-0000-4000-8000-000000000001"}}
                        """);
                case "/auth/v1/user" -> {
                    assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + TOKEN);
                    yield user;
                }
                case "/auth/v1/logout" -> new MockClientHttpResponse(new byte[0], HttpStatus.NO_CONTENT);
                default -> throw new AssertionError(path);
            };
        });
        return new SupabaseAuth(builder, PROJECT, API_KEY);
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
