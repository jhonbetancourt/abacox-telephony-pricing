package com.infomedia.abacox.telephonypricing.cdr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class PbxSpecialRuleInfo {
        private String preOri;
        private List<String> preNo;
        private String preNvo;
        private Integer minLen;
        private String dir;
        private Integer incoming;
        private String nombre;
    }