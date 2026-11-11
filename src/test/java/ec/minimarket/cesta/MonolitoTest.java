package ec.minimarket.cesta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.minimarket.cesta.account.SupabaseAuth;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MonolitoTest {
    private static final String PASSWORD = "ClaveDePrueba321";
    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-4000-8000-000000000010");

    @Autowired MockMvc mvc;
    @MockitoBean SupabaseAuth auth;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    private static final TlsFixture TLS = TlsFixture.create();

    @DynamicPropertySource
    static void tls(DynamicPropertyRegistry properties) {
        properties.add("server.ssl.enabled", () -> true);
        properties.add("server.ssl.key-store", () -> TLS.keyStore().toUri().toString());
        properties.add("server.ssl.key-store-password", () -> TlsFixture.PASSWORD);
    }

    @Test
    void protegeLasPantallasYLasMutaciones() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/chat")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/productos").with(csrf()).with(user("cliente").roles("CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/productos").with(user("operador").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void separaLasPortadasYProtegeElPanelAdministrativo() throws Exception {
        mvc.perform(get("/").with(user("operador").roles("ADMIN")))
                .andExpect(redirectedUrl("/admin"));
        mvc.perform(get("/admin").with(user("operador").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(view().name("admin/index"));
        mvc.perform(get("/").with(user("cliente").roles("CUSTOMER")))
                .andExpect(status().isOk()).andExpect(view().name("catalog/list"));
        mvc.perform(get("/admin").with(user("cliente").roles("CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void registraYConfirmaConPkceEnElMismoNavegador() throws Exception {
        mvc.perform(post("/registro").with(csrf()).param("name", "Luz")
                        .param("email", "correo inválido").param("password", "corta"))
                .andExpect(model().attributeHasFieldErrors("form", "email", "password"));
        MockHttpSession browser = (MockHttpSession) mvc.perform(post("/registro").with(csrf()).param("name", "Luz")
                        .param("email", "Luz@example.test").param("password", PASSWORD))
                .andExpect(redirectedUrl("/ingresar?registro")).andReturn().getRequest().getSession(false);
        verify(auth).register(eq("Luz"), eq("luz@example.test"), eq(PASSWORD),
                matches("[A-Za-z0-9_-]{43}"), eq("http://localhost:8080/confirmar"));
        String code = "codigo-de-confirmacion";
        mvc.perform(get("/confirmar").param("code", code))
                .andExpect(redirectedUrl("/ingresar?enlace"));
        verify(auth, never()).exchange(anyString(), anyString());
        when(auth.exchange(eq(code), matches("[A-Za-z0-9_-]{43}")))
                .thenReturn(identity(UUID.randomUUID(), "luz@example.test", "Luz"));
        mvc.perform(get("/confirmar").session(browser).param("code", code))
                .andExpect(redirectedUrl("/ingresar?confirmada"));
        mvc.perform(get("/confirmar").session(browser).param("code", code))
                .andExpect(redirectedUrl("/ingresar?enlace"));
    }

    @Test
    void asignaElRolPorUuidYNoPorMetadatosDelUsuario() throws Exception {
        when(auth.signIn("luz@example.test", PASSWORD))
                .thenReturn(identity(UUID.randomUUID(), "luz@example.test", "Luz"));
        MockHttpSession cliente = login("LUZ@example.test");
        mvc.perform(get("/productos/nuevo").session(cliente)).andExpect(status().isForbidden());
        when(auth.signIn("operador@example.test", PASSWORD))
                .thenReturn(identity(ADMIN, "operador@example.test", "Administrador"));
        MockHttpSession administrador = login("operador@example.test");
        mvc.perform(get("/productos/nuevo").session(administrador)).andExpect(status().isOk());
        when(auth.signIn("luz@example.test", "incorrecta")).thenThrow(new SupabaseAuth.Rejected());
        mvc.perform(post("/ingresar").with(csrf()).param("email", "luz@example.test")
                        .param("password", "incorrecta"))
                .andExpect(redirectedUrl("/ingresar?error"));
    }

    @Test
    void restableceUnaVezYCierraLasSesionesAnteriores() throws Exception {
        String email = "recuperacion@example.test";
        var verified = identity(UUID.randomUUID(), email, "Luz");
        when(auth.signIn(email, PASSWORD)).thenReturn(verified);
        MockHttpSession antigua = login(email);
        MockHttpSession browser = (MockHttpSession) mvc.perform(post("/recuperar").with(csrf()).param("email", email))
                .andExpect(model().attribute("sent", true)).andReturn().getRequest().getSession(false);
        verify(auth).recover(eq(email), matches("[A-Za-z0-9_-]{43}"), eq("http://localhost:8080/restablecer"));
        String code = "codigo-de-recuperacion";
        mvc.perform(get("/restablecer").param("code", code)).andExpect(model().attribute("invalidToken", true));
        mvc.perform(get("/restablecer").session(browser).param("code", code))
                .andExpect(model().attribute("invalidToken", false));
        verify(auth, never()).exchange(anyString(), anyString());
        when(auth.exchange(eq(code), matches("[A-Za-z0-9_-]{43}"))).thenReturn(verified);
        mvc.perform(post("/restablecer").session(browser).with(csrf()).param("code", code)
                        .param("password", "NuevaClave321"))
                .andExpect(redirectedUrl("/ingresar?restablecida"));
        mvc.perform(post("/restablecer").session(browser).with(csrf()).param("code", code)
                        .param("password", "OtraClave321"))
                .andExpect(model().attribute("invalidToken", true));
        verify(auth).updatePassword(verified, "NuevaClave321");
        verify(auth).revoke(verified, "global");
        mvc.perform(get("/").session(antigua)).andExpect(redirectedUrl("/ingresar?sesion"));
    }

    @Test
    void conservaLaRespuestaDeRecuperacionParaCorreosDesconocidos() throws Exception {
        String email = "caducidad@example.test";
        doThrow(new SupabaseAuth.Rejected()).when(auth).recover(eq(email), anyString(), anyString());
        mvc.perform(post("/recuperar").with(csrf()).param("email", email))
                .andExpect(model().attribute("sent", true));
    }

    @Test
    void creaConsultaYEliminaUnProductoTrasConfirmar() throws Exception {
        String route = createProduct("Leche de prueba");
        mvc.perform(get(route).with(user("cliente").roles("CUSTOMER")))
                .andExpect(status().isOk()).andExpect(view().name("catalog/detail"));
        mvc.perform(post(route + "/eliminar").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("version", "0"))
                .andExpect(redirectedUrl(route));
        mvc.perform(post(route + "/eliminar").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("version", "0").param("confirmar", "true"))
                .andExpect(redirectedUrl("/productos"));
        mvc.perform(get(route).with(user("cliente").roles("CUSTOMER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void rechazaHandshakeAnonimoYEntregaChatPorWss() throws Exception {
        mvc.perform(get("/ws/chat").with(user("cliente").roles("CUSTOMER"))
                        .header("Upgrade", "websocket").header("Connection", "Upgrade"))
                .andExpect(status().isForbidden());

        URI endpoint = URI.create("wss://127.0.0.1:" + port + "/ws/chat");
        assertThatThrownBy(() -> HttpClient.newBuilder().sslContext(TLS.clientContext()).build().newWebSocketBuilder()
                .buildAsync(endpoint, new Inbox()).get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(WebSocketHandshakeException.class);

        when(auth.signIn("ana@example.test", PASSWORD))
                .thenReturn(identity(UUID.randomUUID(), "ana@example.test", "Ana"));
        when(auth.signIn("pablo@example.test", PASSWORD))
                .thenReturn(identity(UUID.randomUUID(), "pablo@example.test", "Pablo"));
        Inbox ana = new Inbox();
        Inbox pablo = new Inbox();
        WebSocket first = connect("ana@example.test", endpoint, ana);
        WebSocket second = connect("pablo@example.test", endpoint, pablo);
        try {
            assertThat(ana.waitFor("presence", 2).get("count").asInt()).isEqualTo(2);
            first.sendText("   ", true).join();
            assertThat(ana.waitFor("error").get("text").asText()).contains("Escribe un mensaje");
            first.sendText("¿Tienen más aguacates?", true).join();
            JsonNode message = pablo.waitFor("message").get("message");
            assertThat(message.get("author").asText()).isEqualTo("Ana");
            assertThat(message.get("content").asText()).isEqualTo("¿Tienen más aguacates?");
            mvc.perform(get("/chat").with(user("pablo@example.test").roles("CUSTOMER")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("¿Tienen más aguacates?")));

            String route = createProduct("Aguacate de prueba");
            mvc.perform(post(route).with(user("admin").roles("ADMIN")).with(csrf())
                            .param("nombre", "Aguacate de prueba").param("categoria", "FRUTAS_Y_VERDURAS")
                            .param("descripcion", "Producto de prueba").param("precio", "1.20")
                            .param("stock", "0").param("version", "0"))
                    .andExpect(redirectedUrl(route));
            assertThat(pablo.waitFor("notice").get("text").asText()).contains("Aguacate de prueba");
        } finally {
            first.abort();
            second.abort();
        }
    }

    private MockHttpSession login(String email) throws Exception {
        return (MockHttpSession) mvc.perform(post("/ingresar").with(csrf()).param("email", email)
                        .param("password", PASSWORD))
                .andExpect(redirectedUrl("/")).andReturn().getRequest().getSession(false);
    }

    private SupabaseAuth.AuthSession identity(UUID id, String email, String name) {
        return new SupabaseAuth.AuthSession("token-solo-de-prueba",
                new SupabaseAuth.Identity(id, email, Instant.now(), false, Map.of("name", name, "role", "ADMIN")));
    }

    private String createProduct(String name) throws Exception {
        return mvc.perform(post("/productos").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("nombre", name).param("categoria", "LACTEOS_Y_HUEVOS")
                        .param("descripcion", "Producto de prueba").param("precio", "1.20").param("stock", "10"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
    }

    private WebSocket connect(String email, URI endpoint, Inbox inbox) throws Exception {
        HttpClient client = HttpClient.newBuilder().sslContext(TLS.clientContext())
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        URI login = URI.create("https://127.0.0.1:" + port + "/ingresar");
        var page = client.send(HttpRequest.newBuilder(login).GET().build(), HttpResponse.BodyHandlers.ofString());
        var csrf = CSRF.matcher(page.body());
        assertThat(csrf.find()).isTrue();
        String form = Map.of("email", email, "password", PASSWORD, "_csrf", csrf.group(1)).entrySet().stream()
                .map(field -> field.getKey() + "=" + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        var request = HttpRequest.newBuilder(login)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElseThrow()).endsWith("/");
        return client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .header("Origin", "https://127.0.0.1:" + port)
                .buildAsync(endpoint, inbox).get(5, TimeUnit.SECONDS);
    }

    private class Inbox implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                frames.add(buffer.toString());
                buffer.setLength(0);
            }
            socket.request(1);
            return null;
        }

        JsonNode waitFor(String type) throws Exception {
            return waitFor(type, -1);
        }

        JsonNode waitFor(String type, int count) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                String frame = frames.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (frame == null) break;
                JsonNode event = json.readTree(frame);
                if (event.get("type").asText().equals(type) && (count < 0 || event.get("count").asInt() == count)) {
                    return event;
                }
            }
            throw new AssertionError("No llegó el evento WebSocket: " + type);
        }
    }
}
