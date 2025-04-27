package com.infomedia.abacox.telephonypricing.cdr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrefixInfo {
    private int minLength;
    private int maxLength;
    private Long localId;
    private Long localExtId;
    private Map<String, List<Long>> prefixMap;
    private Map<Long, List<Long>> telephonyTypeMap;
    private Map<Long, PrefixData> dataMap;
    private Map<String, Map<Long, Integer>> prefixOperatorMap;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PrefixData {
        private Long telephonyTypeId;
        private String telephonyTypeName;
        private String operatorName;
        private String prefix;
        private Long operatorId;
        private Integer telephonyTypeMin;
        private Integer telephonyTypeMax;
        private Integer bandsOk;
    }
}