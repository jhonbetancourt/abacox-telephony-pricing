package com.infomedia.abacox.telephonypricing.component.events;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class CommandCall {
    @NotBlank
    private String command;
    @Size(min = 1)
    private Map<String, Object> arguments;
}
