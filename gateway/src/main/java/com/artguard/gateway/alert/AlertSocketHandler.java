package com.artguard.gateway.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Fans alerts out to every connected dashboard client over WebSocket. Sessions
 * register on connect; {@link #broadcast(Alert)} serializes the alert to JSON
 * and writes it to all open sessions.
 */
@Component
public class AlertSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AlertSocketHandler.class);

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ObjectMapper mapper;

    public AlertSocketHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("Dashboard connected ({} total)", sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    /** Broadcast an alert (wrapped as {type:"alert", data:…}). */
    public void broadcast(Alert alert) {
        send(new Envelope("alert", alert));
    }

    /** Broadcast any payload as a tagged envelope to all dashboards. */
    public void send(Object payload) {
        TextMessage msg;
        try {
            msg = new TextMessage(mapper.writeValueAsString(payload));
        } catch (IOException e) {
            log.warn("ws serialize failed: {}", e.getMessage());
            return;
        }
        for (WebSocketSession s : sessions) {
            if (!s.isOpen()) { sessions.remove(s); continue; }
            try {
                synchronized (s) { s.sendMessage(msg); }
            } catch (IOException e) {
                sessions.remove(s);
            }
        }
    }

    public int connectedClients() {
        return sessions.size();
    }
}
