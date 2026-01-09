package com.multi.vidulum.gateway.config;

import com.multi.vidulum.gateway.handler.EventWebSocketHandler;
import com.multi.vidulum.gateway.service.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Slf4j
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final EventWebSocketHandler eventWebSocketHandler;
    private final JwtService jwtService;

    @Value("${gateway.websocket.endpoint}")
    private String websocketEndpoint;

    @Value("${gateway.websocket.allowed-origins}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(eventWebSocketHandler, websocketEndpoint)
                .addInterceptors(new JwtHandshakeInterceptor(jwtService))
                .setAllowedOrigins(allowedOrigins.split(","));

        log.info("WebSocket handler registered at endpoint: {}", websocketEndpoint);
    }

    @RequiredArgsConstructor
    private static class JwtHandshakeInterceptor implements HandshakeInterceptor {

        private final JwtService jwtService;

        @Override
        public boolean beforeHandshake(
                ServerHttpRequest request,
                ServerHttpResponse response,
                WebSocketHandler wsHandler,
                Map<String, Object> attributes) {

            String query = request.getURI().getQuery();
            if (query == null || !query.contains("token=")) {
                log.warn("WebSocket handshake rejected: missing token");
                return false;
            }

            String token = extractToken(query);
            if (token == null || token.isEmpty()) {
                log.warn("WebSocket handshake rejected: empty token");
                return false;
            }

            try {
                String userId = jwtService.extractUserId(token);
                if (userId == null) {
                    log.warn("WebSocket handshake rejected: invalid token");
                    return false;
                }

                attributes.put("userId", userId);
                log.debug("WebSocket handshake accepted for user: {}", userId);
                return true;

            } catch (Exception e) {
                log.warn("WebSocket handshake rejected: {}", e.getMessage());
                return false;
            }
        }

        @Override
        public void afterHandshake(
                ServerHttpRequest request,
                ServerHttpResponse response,
                WebSocketHandler wsHandler,
                Exception exception) {
            // No action needed after handshake
        }

        private String extractToken(String query) {
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    return param.substring(6);
                }
            }
            return null;
        }
    }
}
