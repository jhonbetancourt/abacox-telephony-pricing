package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Prefix;
import com.infomedia.abacox.telephonypricing.entity.TelephonyTypeConfig;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PrefixInfo { // DTO to mimic PHP's $prefijo['datos'][$prefijoid]
    public Long prefixId;
    public String prefixCode;
    public Long telephonyTypeId;
    public String telephonyTypeName;
    public Long operatorId;
    public String operatorName;
    public Integer telephonyTypeMinLength;
    public Integer telephonyTypeMaxLength;
    public boolean bandOk; // From PREFIJO_BANDAOK
    public int bandsAssociatedCount; // Count from BANDA table

    public PrefixInfo(Prefix p, TelephonyTypeConfig cfg, int bandsCount) {
        this.prefixId = p.getId();
        this.prefixCode = p.getCode();
        this.telephonyTypeId = p.getTelephonyTypeId();
        if (p.getTelephonyType() != null) this.telephonyTypeName = p.getTelephonyType().getName();
        this.operatorId = p.getOperatorId();
        if (p.getOperator() != null) this.operatorName = p.getOperator().getName();
        if (cfg != null) {
            this.telephonyTypeMinLength = cfg.getMinValue();
            this.telephonyTypeMaxLength = cfg.getMaxValue();
        } else { // Fallback if no specific config for country
            this.telephonyTypeMinLength = 0;
            this.telephonyTypeMaxLength = 99; // A large number
        }
        this.bandOk = p.isBandOk();
        this.bandsAssociatedCount = bandsCount;
    }
}