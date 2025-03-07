package com.infomedia.abacox.telephonypricing.component.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.infomedia.abacox.telephonypricing.config.JsonConfig;
import com.infomedia.abacox.telephonypricing.exception.RemoteServiceException;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.UUID;

@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
public class CommandResponseMessage extends WSMessage {
    private boolean success;
    private String exception;
    private String errorMessage;
    private Object result;

    public CommandResponseMessage(UUID id, String source, String exception, String errorMessage) {
        super(id, source, MessageType.COMMAND_RESPONSE);
        this.success = false;
        this.exception = exception;
        this.errorMessage = errorMessage;
    }

    public CommandResponseMessage(CommandRequestMessage commandRequestMessage, String source, String exception, String errorMessage) {
        super(source, MessageType.COMMAND_RESPONSE);
        this.success = false;
        this.exception = exception;
        this.errorMessage = errorMessage;
        setId(commandRequestMessage.getId());
    }

    public CommandResponseMessage(UUID id, String source, Object result) {
        super(id, source, MessageType.COMMAND_RESPONSE);
        this.success = true;
        this.result = result;
    }

    public CommandResponseMessage(CommandRequestMessage commandRequestMessage, String source, Object result) {
        super(source, MessageType.COMMAND_RESPONSE);
        this.success = true;
        this.result = result;
        setId(commandRequestMessage.getId());
    }

    public <T> T getResultAs(Class<T> clazz) {
        if (!success) {
            throw new RemoteServiceException("Command failed: " + errorMessage);
        }
        return JsonConfig.getObjectMapper().convertValue(result, clazz);
    }

    public <T> T getResultAs(TypeReference<T> typeReference) {
        if (!success) {
            throw new RemoteServiceException("Command failed: " + errorMessage);
        }
        return JsonConfig.getObjectMapper().convertValue(result, typeReference);
    }
}
