package com.infomedia.abacox.telephonypricing.messaging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalMessage {

    private String tenant;
    private String sourceModule;
    private String type;
    private Object payload;

    /** True if this is a successful response; false if it represents an error. */
    @Builder.Default
    private boolean success = true;

    /** Username of the user that triggered this message (from SecurityContext). */
    private String actor;

    private String correlationId;
    private String replyTo;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
