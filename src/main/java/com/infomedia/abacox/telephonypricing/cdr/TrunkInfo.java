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
    public List<TrunkRateDetails> rates;

    public List<Long> getAllowedTelephonyTypeIds() {
        if (rates == null) return Collections.emptyList();
        return rates.stream().map(r -> r.telephonyTypeId).distinct().collect(Collectors.toList());
    }

    /**
     * Mimics PHP's $celulink['tr'][$troncal]['celufijo'] logic.
     * A trunk is considered "celufijo" if it ONLY has rates defined for CELLULAR telephony type.
     * @return true if this trunk is exclusively for cellular calls, false otherwise.
     */
    public boolean isCelufijo() {
        if (rates == null || rates.isEmpty()) {
            return false;
        }
        // Check if all defined rates are for cellular and there's at least one such rate.
        boolean hasCellularRate = false;
        for (TrunkRateDetails rate : rates) {
            if (rate.telephonyTypeId != null && rate.telephonyTypeId.equals(TelephonyTypeEnum.CELLULAR.getValue())) {
                hasCellularRate = true;
            } else if (rate.telephonyTypeId != null) {
                // If any rate is for a non-cellular type, it's not exclusively celufijo
                return false;
            }
        }
        return hasCellularRate; // True only if all defined rates were cellular and at least one existed
    }
}