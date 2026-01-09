package com.multi.vidulum.gateway.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multi.vidulum.gateway.dto.ClientMessage;
import com.multi.vidulum.gateway.dto.ServerMessage;
import com.multi.vidulum.gateway.service.SessionRegistry;
import com.multi.vidulum.gateway.service.SubscriptionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

/**
 * Handles WebSocket connections and client messages.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventWebSocketHandler extends TextWebSocketHandler {

    private final SessionRegistry sessionRegistry;
    private final SubscriptionManager subscriptionManager;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionRegistry.register(session);
        String userId = (String) session.getAttributes().get("userId");
        log.info("WebSocket connection established: sessionId={}, userId={}", session.getId(), userId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        subscriptionManager.unsubscribeAll(session);
        sessionRegistry.unregister(session);
        log.info("WebSocket connection closed: sessionId={}, status={}", session.getId(), status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            ClientMessage clientMessage = objectMapper.readValue(message.getPayload(), ClientMessage.class);
            handleClientMessage(session, clientMessage);
        } catch (IOException e) {
            log.warn("Failed to parse client message: {}", e.getMessage());
            sendError(session, "Invalid message format");
        }
    }

    private void handleClientMessage(WebSocketSession session, ClientMessage message) {
        if (message.getAction() == null) {
            sendError(session, "Missing action");
            return;
        }

        switch (message.getAction()) {
            case ping -> handlePing(session);
            case subscribe -> handleSubscribe(session, message);
            case unsubscribe -> handleUnsubscribe(session, message);
        }
    }

    private void handlePing(WebSocketSession session) {
        sendMessage(session, ServerMessage.pong());
    }

    private void handleSubscribe(WebSocketSession session, ClientMessage message) {
        if (message.getTopic() == null || message.getCashFlowId() == null) {
            sendMessage(session, ServerMessage.ack("subscribe", false, "Missing topic or cashFlowId"));
            return;
        }

        subscriptionManager.subscribe(session, message.getTopic(), message.getCashFlowId());
        sendMessage(session, ServerMessage.ack("subscribe", true,
                "Subscribed to " + message.getTopic() + ":" + message.getCashFlowId()));
    }

    private void handleUnsubscribe(WebSocketSession session, ClientMessage message) {
        if (message.getTopic() == null || message.getCashFlowId() == null) {
            sendMessage(session, ServerMessage.ack("unsubscribe", false, "Missing topic or cashFlowId"));
            return;
        }

        subscriptionManager.unsubscribe(session, message.getTopic(), message.getCashFlowId());
        sendMessage(session, ServerMessage.ack("unsubscribe", true,
                "Unsubscribed from " + message.getTopic() + ":" + message.getCashFlowId()));
    }

    private void sendMessage(WebSocketSession session, ServerMessage message) {
        try {
            if (session.isOpen()) {
                String json = objectMapper.writeValueAsString(message);
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.error("Failed to send message to session {}: {}", session.getId(), e.getMessage());
        }
    }

    private void sendError(WebSocketSession session, String errorMessage) {
        sendMessage(session, ServerMessage.error(errorMessage));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Transport error for session {}: {}", session.getId(), exception.getMessage());
    }
}
