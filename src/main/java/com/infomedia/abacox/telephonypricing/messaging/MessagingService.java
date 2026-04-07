package com.infomedia.abacox.telephonypricing.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infomedia.abacox.telephonypricing.service.AuthService;
import com.infomedia.abacox.telephonypricing.service.ReportGenerationService;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

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

    private static final String GENERATE_REPORT_COMMAND = "GENERATE_REPORT";
    private static final String REPORT_GENERATED_EVENT = "REPORT_GENERATED";

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String moduleName;
    private final AuthService authService;
    private final ReportGenerationService reportGenerationService;

    public MessagingService(RabbitTemplate rabbitTemplate,
                            ObjectMapper objectMapper,
                            @Value("${spring.application.name}") String moduleName,
                            AuthService authService,
                            ReportGenerationService reportGenerationService) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.moduleName = moduleName;
        this.authService = authService;
        this.reportGenerationService = reportGenerationService;
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
                .actor(authService.getUsername())
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
                .actor(authService.getUsername())
                .build();

        log.debug("Sending query [{}] to [{}] with timeout {}ms", type, targetModule, timeoutMs);

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
     */
    @RabbitListener(queues = RabbitMQConfig.TELEPHONY_QUERIES_QUEUE)
    public Object handleQuery(InternalMessage request) {
        applyActor(request.getActor());

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
     * Dispatches GENERATE_REPORT commands to the report generation service.
     */
    @RabbitListener(queues = RabbitMQConfig.TELEPHONY_EVENTS_QUEUE)
    public void handleEvent(InternalMessage event) {
        applyActor(event.getActor());
        log.debug("Observed event [{}] from [{}] (tenant: {}, actor: {})",
                event.getType(), event.getSourceModule(), event.getTenant(), event.getActor());

        if (GENERATE_REPORT_COMMAND.equals(event.getType())) {
            handleGenerateReport(event);
        }
    }

    private void handleGenerateReport(InternalMessage event) {
        try {
            Map<String, Object> payload = objectMapper.convertValue(event.getPayload(), new TypeReference<>() {});
            String endpointPath = (String) payload.get("endpointPath");
            String fileName = (String) payload.getOrDefault("fileName", "report.xlsx");
            String tenant = (String) payload.get("tenant");
            String reportId = (String) payload.get("reportId");

            Map<String, String> parameters = payload.containsKey("parameters")
                    ? objectMapper.convertValue(payload.get("parameters"), new TypeReference<>() {})
                    : null;

            log.info("Handling GENERATE_REPORT: endpoint={}, tenant={}, reportId={}", endpointPath, tenant, reportId);

            Map<String, Object> result = reportGenerationService.generateReport(
                    endpointPath, parameters, fileName, tenant);

            result.put("reportId", reportId);
            result.put("success", true);

            publishEvent(tenant, REPORT_GENERATED_EVENT, result);

        } catch (Exception e) {
            log.error("Error handling GENERATE_REPORT command", e);

            Map<String, Object> payload = objectMapper.convertValue(event.getPayload(), new TypeReference<>() {});
            String reportId = (String) payload.get("reportId");
            String tenant = (String) payload.get("tenant");

            Map<String, Object> errorResult = new java.util.LinkedHashMap<>();
            errorResult.put("reportId", reportId);
            errorResult.put("success", false);
            errorResult.put("errorMessage", "Report generation failed: " + e.getMessage());

            publishEvent(tenant, REPORT_GENERATED_EVENT, errorResult);
        }
    }

    /**
     * Populates the Spring SecurityContext with the actor from the incoming message.
     * This allows @CreatedBy / @LastModifiedBy and AuthService.getUsername() to work
     * correctly inside RabbitMQ listener threads.
     */
    private void applyActor(String actor) {
        String name = (actor != null && !actor.isBlank()) ? actor : "system";
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(name, null, Collections.emptyList())
        );
    }
}
