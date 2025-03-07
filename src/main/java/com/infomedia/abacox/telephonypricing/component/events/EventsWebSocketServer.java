package com.infomedia.abacox.telephonypricing.component.events;


import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
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

@Configuration
@EnableWebSocket
@Log4j2
@RequiredArgsConstructor
public class EventsWebSocketServer extends TextWebSocketHandler implements WebSocketConfigurer {

    private final ObjectMapper objectMapper;

    @Value("${spring.application.prefix}")
    private String source;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(this, "/websocket/module").setAllowedOrigins("*");
    }

    private WebSocketSession session = null;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Connected " + session.getRemoteAddress());
        this.session = session;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Disconnected " + session.getRemoteAddress());
        this.session = null;
    }

    private final Map<UUID, CompletableFuture<CommandResponseMessage>> pendingRequests = new ConcurrentHashMap<>();
    private static final long REQUEST_TIMEOUT_SECONDS = 30;

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.info("Received message from " + session.getRemoteAddress() + ": " + message.getPayload());

        WSMessage wsMessage = null;
        try {
            wsMessage = objectMapper.readValue(message.getPayload(), WSMessage.class);
        } catch (Exception e) {
            log.error("Error processing message: " + e.getMessage(), e);
        }

        if(wsMessage!=null&&wsMessage.getMessagetype().equals(MessageType.COMMAND_RESPONSE)){
            try {
                // Try to parse the message as a response
                CommandResponseMessage response = objectMapper.readValue(message.getPayload(), CommandResponseMessage.class);
                CompletableFuture<CommandResponseMessage> future = pendingRequests.remove(response.getId());

                if (future != null) {
                    future.complete(response);
                }
            } catch (Exception e) {
                log.error("Error processing response: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Error occurred on " + session.getRemoteAddress() + ": " + exception.getMessage(), exception);
    }

    public void sendEventMessage(EventType eventType, String content) {
        try {
            WSMessage message = new EventMessage(source, eventType, content);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (IOException e) {
            log.error("Error occurred while sending message to " + session.getRemoteAddress() + ": " + e.getMessage(), e);
        }
    }

    public void sendWSEventMessage(EventType eventType, String channel, String target, String content, String owner) {
        try {
            WSMessage message = new WSEventMessage(source, eventType, channel, target, content, owner);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (IOException e) {
            log.error("Error occurred while sending message to " + session.getRemoteAddress() + ": " + e.getMessage(), e);
        }
    }

    public CommandResponseMessage sendCommandRequestAndAwaitResponse(String command, Map<String, Object> arguments)
            throws IOException, TimeoutException {
        return sendCommandRequestAndAwaitResponse(command, arguments, REQUEST_TIMEOUT_SECONDS);
    }

    public CommandResponseMessage sendCommandRequestAndAwaitResponse(String command, Map<String, Object> arguments, long timeoutSeconds)
            throws IOException, TimeoutException {

        if (session == null || !session.isOpen()) {
            throw new IOException("WebSocket session is not available");
        }

        WSMessage requestMessage = new CommandRequestMessage(source, command, arguments);

        // Create a CompletableFuture to handle the async response
        CompletableFuture<CommandResponseMessage> responseFuture = new CompletableFuture<>();
        pendingRequests.put(requestMessage.getId(), responseFuture);

        try {
            // Send the request
            String requestJson = objectMapper.writeValueAsString(requestMessage);
            session.sendMessage(new TextMessage(requestJson));

            // Wait for the response with timeout
            return responseFuture.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(requestMessage.getId());
            throw new TimeoutException("Request timed out after " + timeoutSeconds + " seconds");
        } catch (Exception e) {
            pendingRequests.remove(requestMessage.getId());
            throw new IOException("Error while sending request or receiving response", e);
        }
    }
}
