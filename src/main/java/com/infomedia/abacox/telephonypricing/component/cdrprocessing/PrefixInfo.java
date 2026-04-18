package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.Prefix;
import com.infomedia.abacox.telephonypricing.db.entity.TelephonyTypeConfig;
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

    /** Flat constructor that avoids a per-row entityManager.find(Prefix.class, id). */
    public static PrefixInfo fromFlat(Long prefixId, String prefixCode,
                                      Long telephonyTypeId, String telephonyTypeName,
                                      Long operatorId, String operatorName,
                                      Integer cfgMin, Integer cfgMax,
                                      boolean bandOk, int bandsCount) {
        PrefixInfo pi = new PrefixInfo();
        pi.prefixId = prefixId;
        pi.prefixCode = prefixCode;
        pi.telephonyTypeId = telephonyTypeId;
        pi.telephonyTypeName = telephonyTypeName;
        pi.operatorId = operatorId;
        pi.operatorName = operatorName;
        pi.telephonyTypeMinLength = cfgMin != null ? cfgMin : 0;
        pi.telephonyTypeMaxLength = cfgMax != null ? cfgMax : 99;
        pi.bandOk = bandOk;
        pi.bandsAssociatedCount = bandsCount;
        return pi;
    }
}