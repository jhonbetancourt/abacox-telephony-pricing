package com.infomedia.abacox.telephonypricing.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Central service for internal inter-module communication over RabbitMQ.
 *
 * <h3>Publishing events</h3>
 * <pre>
 *   messagingService.publishEvent(TenantContext.getTenant(), "CDR_PROCESSED", payload);
 * </pre>
 *
 * <h3>Sending queries (RPC)</h3>
 * <pre>
 *   InternalMessage response = messagingService.sendQuery("control", "MODULE_INFO_BY_PREFIX_QUERY",
 *       Map.of("prefix", "users"), 5000);
 * </pre>
 */
@Service
@Log4j2
public class MessagingService {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String moduleName;

    public MessagingService(RabbitTemplate rabbitTemplate,
                            ObjectMapper objectMapper,
                            @Value("${spring.application.name}") String moduleName) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.moduleName = moduleName;
    }

    /**
     * Publish a fire-and-forget event.
     *
     * @param tenant  tenant schema, or null for platform-wide
     * @param type    event type (e.g. "CDR_PROCESSED")
     * @param payload arbitrary payload
     */
    public void publishEvent(String tenant, String type, Object payload) {
        String schema = tenant != null ? tenant : "all";
        String routingKey = schema + "." + moduleName + "." + type;

        InternalMessage message = InternalMessage.builder()
                .tenant(tenant)
                .sourceModule(moduleName)
                .type(type)
                .payload(payload)
                .build();

        log.debug("Publishing event [{}] with routing key [{}]", type, routingKey);
        rabbitTemplate.convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, routingKey, message);
    }

    /**
     * Send a synchronous query and wait for a response (RPC pattern).
     *
     * @param targetModule module to query (e.g. "control", "users")
     * @param type         query type (e.g. "MODULE_INFO_BY_PREFIX_QUERY")
     * @param payload      query payload
     * @param timeoutMs    max wait in milliseconds
     * @return response message, or null if timed out
     */
    public InternalMessage sendQuery(String targetModule, String type, Object payload, long timeoutMs) {
        InternalMessage request = InternalMessage.builder()
                .sourceModule(moduleName)
                .type(type)
                .payload(payload)
                .build();

        log.debug("Sending query [{}] to [{}]", type, targetModule);

        Object response = rabbitTemplate.convertSendAndReceive(
                RabbitMQConfig.QUERIES_EXCHANGE,
                targetModule,
                request,
                msg -> {
                    msg.getMessageProperties().setExpiration(String.valueOf(timeoutMs));
                    return msg;
                }
        );

        if (response == null) {
            log.warn("Query [{}] to [{}] timed out after {}ms", type, targetModule, timeoutMs);
            return null;
        }

        if (response instanceof InternalMessage msg) {
            return msg;
        }

        try {
            return objectMapper.convertValue(response, InternalMessage.class);
        } catch (Exception e) {
            log.error("Failed to deserialize query response for [{}]: {}", type, e.getMessage());
            return null;
        }
    }

    /**
     * Handles inbound queries directed at telephony-pricing.
     * Extend this to support module-specific query types.
     */
    @RabbitListener(queues = RabbitMQConfig.TELEPHONY_QUERIES_QUEUE)
    public Object handleQuery(InternalMessage request) {
        log.warn("Received unhandled query [{}] from [{}]", request.getType(), request.getSourceModule());
        return InternalMessage.builder()
                .sourceModule(moduleName)
                .type(request.getType())
                .correlationId(request.getCorrelationId())
                .success(false)
                .payload("No handler registered for query type: " + request.getType())
                .build();
    }

    /**
     * Observes all events from the platform.
     */
    @RabbitListener(queues = RabbitMQConfig.TELEPHONY_EVENTS_QUEUE)
    public void handleEvent(InternalMessage event) {
        log.debug("Observed event [{}] from [{}] (tenant: {})",
                event.getType(), event.getSourceModule(), event.getTenant());
    }
}
