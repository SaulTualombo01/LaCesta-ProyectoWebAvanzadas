package ec.minimarket.cesta.chat;

import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
class ChatWebSocketConfig implements WebSocketConfigurer {
    private final ChatSocket socket;

    ChatWebSocketConfig(ChatSocket socket) {
        this.socket = socket;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var sessionId = new HttpSessionHandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler handler, Map<String, Object> attributes) throws Exception {
                if (!(request instanceof ServletServerHttpRequest servlet) || !servlet.getServletRequest().isSecure()) {
                    response.setStatusCode(HttpStatus.FORBIDDEN);
                    return false;
                }
                return super.beforeHandshake(request, response, handler, attributes);
            }
        };
        sessionId.setCopyAllAttributes(false);
        registry.addHandler(socket, "/ws/chat").addInterceptors(sessionId);
    }
}

