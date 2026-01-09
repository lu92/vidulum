package com.multi.vidulum.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

/**
 * Manages subscriptions to topics and cashFlowIds.
 * Allows clients to subscribe to specific events for specific CashFlows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionManager {

    private final SessionRegistry sessionRegistry;

    // topic:cashFlowId -> Set<sessionId>
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();

    // sessionId -> Set<topic:cashFlowId> (for cleanup on disconnect)
    private final Map<String, Set<String>> sessionSubscriptions = new ConcurrentHashMap<>();

    public void subscribe(WebSocketSession session, String topic, String cashFlowId) {
        String sessionId = session.getId();
        String subscriptionKey = buildKey(topic, cashFlowId);

        subscriptions.computeIfAbsent(subscriptionKey, k -> new CopyOnWriteArraySet<>())
                .add(sessionId);

        sessionSubscriptions.computeIfAbsent(sessionId, k -> new CopyOnWriteArraySet<>())
                .add(subscriptionKey);

        log.info("Subscription added: sessionId={}, topic={}, cashFlowId={}",
                sessionId, topic, cashFlowId);
    }

    public void unsubscribe(WebSocketSession session, String topic, String cashFlowId) {
        String sessionId = session.getId();
        String subscriptionKey = buildKey(topic, cashFlowId);

        Set<String> subscribers = subscriptions.get(subscriptionKey);
        if (subscribers != null) {
            subscribers.remove(sessionId);
            if (subscribers.isEmpty()) {
                subscriptions.remove(subscriptionKey);
            }
        }

        Set<String> sessionSubs = sessionSubscriptions.get(sessionId);
        if (sessionSubs != null) {
            sessionSubs.remove(subscriptionKey);
        }

        log.info("Subscription removed: sessionId={}, topic={}, cashFlowId={}",
                sessionId, topic, cashFlowId);
    }

    public void unsubscribeAll(WebSocketSession session) {
        String sessionId = session.getId();
        Set<String> sessionSubs = sessionSubscriptions.remove(sessionId);

        if (sessionSubs != null) {
            for (String subscriptionKey : sessionSubs) {
                Set<String> subscribers = subscriptions.get(subscriptionKey);
                if (subscribers != null) {
                    subscribers.remove(sessionId);
                    if (subscribers.isEmpty()) {
                        subscriptions.remove(subscriptionKey);
                    }
                }
            }
            log.info("All subscriptions removed for session: sessionId={}, count={}",
                    sessionId, sessionSubs.size());
        }
    }

    public Set<WebSocketSession> getSubscribers(String topic, String cashFlowId) {
        String subscriptionKey = buildKey(topic, cashFlowId);
        Set<String> sessionIds = subscriptions.getOrDefault(subscriptionKey, Set.of());

        return sessionIds.stream()
                .map(sessionRegistry::getSession)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .filter(WebSocketSession::isOpen)
                .collect(Collectors.toSet());
    }

    public boolean isSubscribed(WebSocketSession session, String topic, String cashFlowId) {
        String subscriptionKey = buildKey(topic, cashFlowId);
        Set<String> subscribers = subscriptions.get(subscriptionKey);
        return subscribers != null && subscribers.contains(session.getId());
    }

    public int getSubscriptionCount() {
        return subscriptions.values().stream()
                .mapToInt(Set::size)
                .sum();
    }

    private String buildKey(String topic, String cashFlowId) {
        return topic + ":" + cashFlowId;
    }
}
