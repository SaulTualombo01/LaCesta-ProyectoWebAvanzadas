package ec.minimarket.cesta.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.minimarket.cesta.account.CestaPrincipal;
import ec.minimarket.cesta.catalog.ProductoAgotado;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private ChatSocket chatSocket;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private DataSource dataSource;

    private final List<WebSocketSession> activeSessions = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (WebSocketSession session : activeSessions) {
            chatSocket.afterConnectionClosed(session, CloseStatus.NORMAL);
        }
        activeSessions.clear();
        chatMessageRepository.deleteAll();
    }

    @Test
    void normalizaContenidoYRechazaMensajesVaciosOExcesivos() {
        ChatEntry saved = chatService.save("Ana", "  Hola\r\nmundo\u0007  ");
        assertThat(saved.author()).isEqualTo("Ana");
        assertThat(saved.content()).isEqualTo("Hola\nmundo");

        assertThatThrownBy(() -> chatService.save("Ana", "   \n\t  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Escribe un mensaje");

        String tooLong = "a".repeat(401);
        assertThatThrownBy(() -> chatService.save("Ana", tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("400 caracteres");
    }

    @Test
    void limitaElHistorialALosUltimosCincuentaMensajesEnOrdenCronologico() {
        chatMessageRepository.deleteAll();
        for (int i = 1; i <= 55; i++) {
            chatService.save("Cliente", "Mensaje #" + i);
        }

        List<ChatEntry> history = chatService.history();
        assertThat(history).hasSize(50);
        assertThat(history.get(0).content()).isEqualTo("Mensaje #6");
        assertThat(history.get(49).content()).isEqualTo("Mensaje #55");
    }

    @Test
    void renderizaLaSalaConContextoDeUsuarioYComponentesInteractivos() throws Exception {
        var auth = authToken("Ana Cliente", "ROLE_CUSTOMER");

        mvc.perform(get("/chat").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(view().name("chat/index"))
                .andExpect(model().attribute("currentUser", "Ana Cliente"))
                .andExpect(model().attribute("isAdmin", false))
                .andExpect(content().string(containsString("id=\"chat-search\"")))
                .andExpect(content().string(containsString("id=\"chat-users-list\"")))
                .andExpect(content().string(containsString("id=\"chat-staff-status\"")))
                .andExpect(content().string(containsString("id=\"chat-typing\"")))
                .andExpect(content().string(containsString("id=\"chat-char-count\"")))
                .andExpect(content().string(containsString("class=\"chat-chip\"")));
    }

    @Test
    void difundePresenciaConListaDeUsuariosEstadoDePersonalYEventoTyping() throws Exception {
        List<JsonNode> anaFrames = new ArrayList<>();
        List<JsonNode> saulFrames = new ArrayList<>();
        WebSocketSession anaSession = mockSession("s-ana", "Ana", "ROLE_CUSTOMER", anaFrames);
        WebSocketSession saulSession = mockSession("s-saul", "Saúl", "ROLE_ADMIN", saulFrames);

        chatSocket.afterConnectionEstablished(anaSession);
        chatSocket.afterConnectionEstablished(saulSession);

        JsonNode latestPresence = lastFrameOfType(anaFrames, "presence");
        assertThat(latestPresence.get("count").asInt()).isEqualTo(2);
        assertThat(latestPresence.get("staffOnline").asBoolean()).isTrue();
        assertThat(latestPresence.get("users"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("Ana", "Saúl");

        long messagesBeforeTyping = chatMessageRepository.count();
        chatSocket.handleTextMessage(anaSession, new TextMessage("{\"type\":\"typing\",\"active\":true}"));

        JsonNode typingFrame = lastFrameOfType(saulFrames, "typing");
        assertThat(typingFrame.get("author").asText()).isEqualTo("Ana");
        assertThat(typingFrame.get("role").asText()).isEqualTo("CUSTOMER");
        assertThat(typingFrame.get("active").asBoolean()).isTrue();
        assertThat(anaFrames).noneMatch(frame -> "typing".equals(frame.get("type").asText()));
        assertThat(chatMessageRepository.count()).isEqualTo(messagesBeforeTyping);

        chatSocket.handleTextMessage(saulSession, new TextMessage("Sí tenemos stock disponible."));
        JsonNode messageFrame = lastFrameOfType(anaFrames, "message").get("message");
        assertThat(messageFrame.get("author").asText()).isEqualTo("Saúl");
        assertThat(messageFrame.get("role").asText()).isEqualTo("ADMIN");
        assertThat(messageFrame.get("content").asText()).isEqualTo("Sí tenemos stock disponible.");

        chatSocket.notifyStock(new ProductoAgotado(1L, "Café lojano"));
        JsonNode noticeFrame = lastFrameOfType(anaFrames, "notice");
        assertThat(noticeFrame.get("text").asText()).contains("Café lojano");
        assertThat(noticeFrame.get("sentAt").asText()).isNotBlank();

        chatSocket.afterConnectionClosed(saulSession, CloseStatus.NORMAL);
        JsonNode updatedPresence = lastFrameOfType(anaFrames, "presence");
        assertThat(updatedPresence.get("count").asInt()).isEqualTo(1);
        assertThat(updatedPresence.get("staffOnline").asBoolean()).isFalse();
        assertThat(updatedPresence.get("users"))
                .extracting(JsonNode::asText)
                .containsExactly("Ana");
    }

    @Test
    void mideTiempoDeRespuestaYVerificaIndiceEnConsultaPaginadaYChat() throws Exception {
        String[] categorias = {"BEBIDAS", "DESPENSA", "FRUTAS_Y_VERDURAS", "LACTEOS_Y_HUEVOS", "LIMPIEZA", "OTROS"};
        String sqlInsert = "INSERT INTO la_cesta.productos (nombre, categoria, descripcion, precio, stock) "
                + "VALUES (?, ?, ?, 1.50, 10)";
        String sqlPagina = "SELECT id, nombre FROM la_cesta.productos WHERE categoria = 'BEBIDAS' "
                + "ORDER BY id DESC LIMIT 8";

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement insert = conn.prepareStatement(sqlInsert)) {
                for (int i = 1; i <= 10_000; i++) {
                    insert.setString(1, "Bench-" + i);
                    insert.setString(2, categorias[i % categorias.length]);
                    insert.setString(3, "Producto ficticio de prueba de rendimiento");
                    insert.addBatch();
                    if (i % 1_000 == 0) {
                        insert.executeBatch();
                    }
                }
                insert.executeBatch();
                conn.commit();
            } catch (Exception failure) {
                conn.rollback();
                throw failure;
            } finally {
                conn.setAutoCommit(true);
            }

            try (Statement st = conn.createStatement()) {
                st.execute("CREATE INDEX IF NOT EXISTS la_cesta.productos_categoria_id_idx "
                        + "ON la_cesta.productos (categoria, id DESC)");
                try (ResultSet explain = st.executeQuery("EXPLAIN ANALYZE " + sqlPagina)) {
                    StringBuilder plan = new StringBuilder();
                    while (explain.next()) {
                        plan.append(explain.getString(1)).append('\n');
                    }
                    String planText = plan.toString();
                    assertThat(planText).doesNotContainIgnoringCase("tableScan");
                    assertThat(planText).contains("categoria = 'BEBIDAS'");
                }
            }

            try (PreparedStatement query = conn.prepareStatement(sqlPagina)) {
                for (int i = 0; i < 20; i++) {
                    try (ResultSet rs = query.executeQuery()) {
                        while (rs.next()) {
                            rs.getLong(1);
                            rs.getString(2);
                        }
                    }
                }

                long[] tiemposNs = new long[100];
                for (int i = 0; i < tiemposNs.length; i++) {
                    long inicio = System.nanoTime();
                    int filas = 0;
                    try (ResultSet rs = query.executeQuery()) {
                        while (rs.next()) {
                            rs.getLong(1);
                            rs.getString(2);
                            filas++;
                        }
                    }
                    tiemposNs[i] = System.nanoTime() - inicio;
                    assertThat(filas).isEqualTo(8);
                }

                Arrays.sort(tiemposNs);
                double medianaMs = tiemposNs[tiemposNs.length / 2] / 1_000_000.0;
                double p95Ms = tiemposNs[(int) Math.ceil(tiemposNs.length * 0.95) - 1] / 1_000_000.0;

                for (int i = 1; i <= 50; i++) {
                    chatService.save("Cliente", "Mensaje rendimiento #" + i);
                }
                long inicioChat = System.nanoTime();
                List<ChatEntry> historial = chatService.history();
                double chatMs = (System.nanoTime() - inicioChat) / 1_000_000.0;

                System.out.printf(
                        Locale.US,
                        "[RENDIMIENTO] Consulta paginada (10 000 productos, indice productos_categoria_id_idx): "
                                + "mediana=%.3f ms, p95=%.3f ms | Historial chat (50 mensajes)=%.3f ms%n",
                        medianaMs, p95Ms, chatMs);

                assertThat(medianaMs).isLessThan(10.0);
                assertThat(p95Ms).isLessThan(15.0);
                assertThat(historial).hasSize(50);
                assertThat(chatMs).isLessThan(25.0);
            } finally {
                try (Statement cleanup = conn.createStatement()) {
                    cleanup.executeUpdate("DELETE FROM la_cesta.productos WHERE nombre LIKE 'Bench-%'");
                }
            }
        }
    }

    private UsernamePasswordAuthenticationToken authToken(String displayName, String role) {
        var principal = new CestaPrincipal(UUID.randomUUID(), displayName);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, AuthorityUtils.createAuthorityList(role));
    }

    private WebSocketSession mockSession(String id, String displayName, String role, List<JsonNode> sink)
            throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getPrincipal()).thenReturn(authToken(displayName, role));
        when(session.getAttributes()).thenReturn(Map.of(
                HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME, "http-" + id));
        doAnswer(invocation -> {
            TextMessage payload = invocation.getArgument(0);
            sink.add(json.readTree(payload.getPayload()));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        activeSessions.add(session);
        return session;
    }

    private JsonNode lastFrameOfType(List<JsonNode> frames, String type) {
        for (int i = frames.size() - 1; i >= 0; i--) {
            if (type.equals(frames.get(i).get("type").asText())) {
                return frames.get(i);
            }
        }
        throw new AssertionError("No se encontró ningún frame del tipo: " + type);
    }
}
