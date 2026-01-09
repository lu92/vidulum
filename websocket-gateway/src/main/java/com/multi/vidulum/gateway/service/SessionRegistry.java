package com.multi.vidulum.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Manages WebSocket sessions.
 * Thread-safe registry for all active WebSocket connections.
 */
@Slf4j
@Service
public class SessionRegistry {

    // sessionId -> WebSocketSession
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    // userId -> Set<sessionId> (one user can have multiple sessions/tabs)
    private final Map<String, Set<String>> userSessions = new ConcurrentHashMap<>();

    public void register(WebSocketSession session) {
        String sessionId = session.getId();
        String userId = getUserId(session);

        sessions.put(sessionId, session);

        if (userId != null) {
            userSessions.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>())
                    .add(sessionId);
        }

        log.info("Session registered: sessionId={}, userId={}, totalSessions={}",
                sessionId, userId, sessions.size());
    }

    public void unregister(WebSocketSession session) {
        String sessionId = session.getId();
        String userId = getUserId(session);

        sessions.remove(sessionId);

        if (userId != null) {
            Set<String> userSessionIds = userSessions.get(userId);
            if (userSessionIds != null) {
                userSessionIds.remove(sessionId);
                if (userSessionIds.isEmpty()) {
                    userSessions.remove(userId);
                }
            }
        }

        log.info("Session unregistered: sessionId={}, userId={}, totalSessions={}",
                sessionId, userId, sessions.size());
    }

    public Optional<WebSocketSession> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public Set<String> getSessionIds(String userId) {
        return userSessions.getOrDefault(userId, Set.of());
    }

    public Collection<WebSocketSession> getAllSessions() {
        return sessions.values();
    }

    public int getSessionCount() {
        return sessions.size();
    }

    public int getUserCount() {
        return userSessions.size();
    }

    private String getUserId(WebSocketSession session) {
        Object userId = session.getAttributes().get("userId");
        return userId != null ? userId.toString() : null;
    }
}
