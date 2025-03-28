package com.infomedia.abacox.telephonypricing.dto.series;

import com.infomedia.abacox.telephonypricing.dto.superclass.AuditedLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SeriesLegacyMapping extends AuditedLegacyMapping {
    private String id;
    private String indicatorId;
    private String ndc;
    private String initialNumber;
    private String finalNumber;
    private String company;
}