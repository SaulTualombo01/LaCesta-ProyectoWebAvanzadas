package ec.minimarket.cesta.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.minimarket.cesta.account.AccountSessionsExpired;
import ec.minimarket.cesta.account.CestaPrincipal;
import ec.minimarket.cesta.catalog.ProductoAgotado;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionDestroyedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

@Component
class ChatSocket extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatSocket.class);

    private final Map<String, WebSocketSession> connections = new ConcurrentHashMap<>();
    private final ChatService chat;
    private final ObjectMapper json;

    ChatSocket(ChatService chat, ObjectMapper json) {
        this.chat = chat;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (!(session.getPrincipal() instanceof Authentication authentication)
                || !(authentication.getPrincipal() instanceof CestaPrincipal)
                || !session.getAttributes().containsKey(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        session.setTextMessageSizeLimit(4096);
        var connection = new ConcurrentWebSocketSessionDecorator(session, 5_000, 65_536);
        connections.put(session.getId(), connection);
        send(connection, frame("history", "messages", chat.history()));
        broadcastPresence();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        WebSocketSession connection = connections.get(session.getId());
        if (connection == null) {
            return;
        }
        try {
            var authentication = (Authentication) connection.getPrincipal();
            var user = (CestaPrincipal) authentication.getPrincipal();
            boolean admin = isAdmin(authentication);
            if (tryHandleTyping(connection, user, admin, message.getPayload())) {
                return;
            }
            ChatEntry entry = chat.save(user.displayName(), message.getPayload());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", entry.id());
            payload.put("author", entry.author());
            payload.put("content", entry.content());
            payload.put("sentAt", entry.sentAt());
            payload.put("role", admin ? "ADMIN" : "CUSTOMER");
            broadcast(frame("message", "message", payload));
        } catch (IllegalArgumentException invalid) {
            send(connection, frame("error", "text", invalid.getMessage()));
        } catch (DataAccessException failure) {
            log.warn("No se pudo persistir un mensaje de chat.", failure);
            send(connection, frame("error", "text", "No pudimos guardar el mensaje. Vuelve a intentarlo."));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (connections.remove(session.getId()) != null) {
            broadcastPresence();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        close(session, CloseStatus.SERVER_ERROR);
    }

    @EventListener
    void endSession(SessionDestroyedEvent event) {
        connections.values().forEach(connection -> {
            Object id = connection.getAttributes().get(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME);
            if (event.getId().equals(id)) {
                close(connection, CloseStatus.POLICY_VIOLATION);
            }
        });
    }

    @EventListener
    void endAccountSessions(AccountSessionsExpired event) {
        String account = event.userId().toString();
        connections.values().stream()
                .filter(connection -> account.equals(connection.getPrincipal().getName()))
                .forEach(connection -> close(connection, CloseStatus.POLICY_VIOLATION));
    }

    @TransactionalEventListener
    void notifyStock(ProductoAgotado event) {
        broadcast(frame("notice", "text", "Producto agotado: " + event.nombre() + "."));
    }

    private boolean tryHandleTyping(WebSocketSession sender, CestaPrincipal user, boolean admin, String rawPayload) {
        String trimmed = rawPayload == null ? "" : rawPayload.strip();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return false;
        }
        try {
            JsonNode node = json.readTree(trimmed);
            if (!node.isObject() || !"typing".equals(node.path("type").asText()) || node.size() > 2) {
                return false;
            }
            var fields = node.fieldNames();
            while (fields.hasNext()) {
                String name = fields.next();
                if (!"type".equals(name) && !"active".equals(name)) {
                    return false;
                }
            }
            boolean active = !node.has("active") || node.get("active").asBoolean(true);
            TextMessage frame = jsonFrame(Map.of(
                    "type", "typing",
                    "author", user.displayName(),
                    "role", admin ? "ADMIN" : "CUSTOMER",
                    "active", active));
            connections.values().stream()
                    .filter(target -> !target.getId().equals(sender.getId()))
                    .forEach(target -> send(target, frame));
            return true;
        } catch (JsonProcessingException ignored) {
            return false;
        }
    }

    private synchronized void broadcastPresence() {
        Map<String, String> onlineUsers = new LinkedHashMap<>();
        boolean staffOnline = false;
        for (WebSocketSession connection : connections.values()) {
            if (!connection.isOpen() || !(connection.getPrincipal() instanceof Authentication authentication)) {
                continue;
            }
            if (authentication.getPrincipal() instanceof CestaPrincipal principal) {
                onlineUsers.putIfAbsent(principal.getName(), principal.displayName());
            }
            if (isAdmin(authentication)) {
                staffOnline = true;
            }
        }
        broadcast(jsonFrame(Map.of(
                "type", "presence",
                "count", (long) onlineUsers.size(),
                "users", List.copyOf(onlineUsers.values()),
                "staffOnline", staffOnline)));
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    private void broadcast(TextMessage message) {
        connections.values().forEach(connection -> send(connection, message));
    }

    private void send(WebSocketSession connection, TextMessage message) {
        try {
            if (connection.isOpen()) {
                connection.sendMessage(message);
            }
        } catch (IOException | IllegalStateException failure) {
            close(connection, CloseStatus.SERVER_ERROR);
        }
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException failure) {
            log.debug("La conexión WebSocket ya no está disponible.", failure);
        }
    }

    private TextMessage frame(String type, String key, Object data) {
        return jsonFrame(Map.of("type", type, key, data));
    }

    private TextMessage jsonFrame(Map<String, Object> payload) {
        try {
            return new TextMessage(json.writeValueAsString(payload));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("No se pudo codificar el evento de chat.", failure);
        }
    }
}
