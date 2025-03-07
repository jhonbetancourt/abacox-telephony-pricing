package com.infomedia.abacox.telephonypricing.dto.module;

import com.infomedia.abacox.telephonypricing.component.events.EventType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class EventTypesInfo {
    private List<EventType> produces;
    private List<EventType> consumes;
}
