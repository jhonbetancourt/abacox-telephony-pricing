package com.infomedia.abacox.telephonypricing.component.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.infomedia.abacox.telephonypricing.config.JsonConfig;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Data
public class WSEventMessage extends WSMessage{
    private String owner;
    private EventType eventType;
    private String channel;
    private String target;
    private JsonNode content;

    public WSEventMessage(String source, EventType eventType, String channel, String target, String content, String owner) {
        super(UUID.randomUUID(), source, LocalDateTime.now(), MessageType.WS_EVENT);
        this.eventType = eventType;
        this.channel = channel;
        this.target = target;
        this.owner = owner;
        try {
            this.content = JsonConfig.getObjectMapper().readTree(content);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
