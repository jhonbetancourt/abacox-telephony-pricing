package com.infomedia.abacox.telephonypricing.cdr;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class TrunkInfo {
    public Long id;
    public String description;
    public Long operatorId;
    public Boolean noPbxPrefix;
    // public boolean normalize; // Removed in PHP
    public List<TrunkRateDetails> rates; // Mimics $celulink['tr'][$troncal]['operador_destino']

    public List<Long> getAllowedTelephonyTypeIds() {
        if (rates == null) return Collections.emptyList();
        return rates.stream().map(r -> r.telephonyTypeId).distinct().collect(Collectors.toList());
    }
}
