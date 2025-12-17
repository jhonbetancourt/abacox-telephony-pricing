package com.infomedia.abacox.telephonypricing.component.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@Configuration
@EnableWebSocket
@Log4j2
@RequiredArgsConstructor
public class EventsWebSocketServer extends TextWebSocketHandler implements WebSocketConfigurer {

    private final ObjectMapper objectMapper;

    @Value("${spring.application.prefix}")
    private String source;

    // Thread-safe container for the active session
    private final AtomicReference<WebSocketSession> activeSession = new AtomicReference<>();

    private final Map<UUID, CompletableFuture<CommandResponseMessage>> pendingRequests = new ConcurrentHashMap<>();
    private static final long REQUEST_TIMEOUT_SECONDS = 30;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(this, "/websocket/module").setAllowedOrigins("*");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Connected " + session.getRemoteAddress());
        WebSocketSession oldSession = activeSession.getAndSet(session);
        if (oldSession != null && oldSession.isOpen()) {
            try { oldSession.close(CloseStatus.POLICY_VIOLATION); } catch (IOException ignored) {}
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Disconnected " + session.getRemoteAddress());
        activeSession.compareAndSet(session, null);
    }

    /**
     * HEARTBEAT: Sends a JSON WSMessage with MessageType.PING.
     * This ensures the Client receives data to reset its read-timeout.
     */
    @Scheduled(fixedRate = 30000)
    public void sendHeartbeat() {
        WebSocketSession session = activeSession.get();
        if (session != null && session.isOpen()) {
            try {
                // Create the PING message using your WSMessage class
                WSMessage pingMessage = new WSMessage(source, MessageType.PING);
                String json = objectMapper.writeValueAsString(pingMessage);
                
                // Synchronize to prevent frame interleaving
                synchronized (session) {
                    session.sendMessage(new TextMessage(json));
                }
                log.trace("Heartbeat sent to {}", session.getRemoteAddress());
            } catch (IOException e) {
                log.warn("Heartbeat failed. Connection is dead. Closing session.");
                try { session.close(); } catch (IOException ignored) {}
                activeSession.compareAndSet(session, null);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("Received message: {}", message.getPayload());
        try {
            if (message.getPayload().contains("COMMAND_RESPONSE")) {
                CommandResponseMessage response = objectMapper.readValue(message.getPayload(), CommandResponseMessage.class);
                CompletableFuture<CommandResponseMessage> future = pendingRequests.remove(response.getId());
                if (future != null) future.complete(response);
            }
        } catch (Exception e) {
            log.error("Error processing message: " + e.getMessage(), e);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Transport error: {}", exception.getMessage());
        try { if (session.isOpen()) session.close(); } catch (IOException ignored) {}
    }

    public void sendEventMessage(EventType eventType, String content) {
        WebSocketSession session = activeSession.get();
        if (session == null || !session.isOpen()) return;

        try {
            WSMessage message = new EventMessage(source, eventType, content);
            String json = objectMapper.writeValueAsString(message);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.error("Error sending event: {}", e.getMessage());
            try { session.close(); } catch (IOException ignored) {}
        }
    }

    public CommandResponseMessage sendCommandRequestAndAwaitResponse(String command, Map<String, Object> arguments)
            throws IOException, TimeoutException {
        return sendCommandRequestAndAwaitResponse(command, arguments, REQUEST_TIMEOUT_SECONDS);
    }

    public CommandResponseMessage sendCommandRequestAndAwaitResponse(String command, Map<String, Object> arguments, long timeoutSeconds)
            throws IOException, TimeoutException {

        WebSocketSession session = activeSession.get();
        if (session == null || !session.isOpen()) throw new IOException("WebSocket session closed");

        WSMessage requestMessage = new CommandRequestMessage(source, command, arguments);
        CompletableFuture<CommandResponseMessage> responseFuture = new CompletableFuture<>();
        pendingRequests.put(requestMessage.getId(), responseFuture);

        try {
            String requestJson = objectMapper.writeValueAsString(requestMessage);
            synchronized (session) {
                session.sendMessage(new TextMessage(requestJson));
            }
            return responseFuture.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(requestMessage.getId());
            throw new TimeoutException("Request timed out");
        } catch (Exception e) {
            pendingRequests.remove(requestMessage.getId());
            try { session.close(); } catch (Exception ignored) {}
            throw new IOException("Error sending request", e);
        }
    }
}