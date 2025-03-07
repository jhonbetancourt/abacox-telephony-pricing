package com.infomedia.abacox.telephonypricing.component.events;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
public class WSMessage {
    private UUID id;
    private String source;
    private LocalDateTime timestamp;
    private MessageType messagetype;

    public WSMessage(UUID id, String source, MessageType messagetype) {
        this.id = id;
        this.source = source;
        this.timestamp = LocalDateTime.now();
        this.messagetype = messagetype;
    }

    public WSMessage(String source, MessageType messagetype) {
        this.id = UUID.randomUUID();
        this.source = source;
        this.timestamp = LocalDateTime.now();
        this.messagetype = messagetype;
    }

    public WSMessage(UUID id, String source, LocalDateTime timestamp, MessageType messagetype) {
        this.id = id;
        this.source = source;
        this.timestamp = timestamp;
        this.messagetype = messagetype;
    }
}
