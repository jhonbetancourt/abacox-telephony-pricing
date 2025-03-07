package com.infomedia.abacox.telephonypricing.component.events;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Map;

@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
public class CommandRequestMessage extends WSMessage{
    private String command;
    private Map<String, Object> arguments;

    public CommandRequestMessage(String source, String command, Map<String, Object> arguments) {
        super(source, MessageType.COMMAND_REQUEST);
        this.command = command;
        this.arguments = arguments;
    }
}
