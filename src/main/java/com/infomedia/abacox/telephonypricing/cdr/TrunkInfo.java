package com.infomedia.abacox.telephonypricing.cdr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrunkInfo {
    private boolean cellFixed;
    private String description;
    private Long operatorId;
    private boolean noPbxPrefix;
    private BigDecimal pricePerMinute;
    private boolean pricePerMinuteIncludesVat;
    private BigDecimal vatAmount;
    private boolean inSeconds;
    private Map<Long, Map<Long, Map<Long, TrunkOperatorDestination>>> operatorDestination;
    private Map<Long, List<Long>> operatorDestinationTypes;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrunkOperatorDestination {
        private BigDecimal pricePerMinute;
        private boolean pricePerMinuteIncludesVat;
        private boolean inSeconds;
        private boolean noPbxPrefix;
        private boolean noPrefix;
    }
}