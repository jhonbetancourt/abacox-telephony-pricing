package com.infomedia.abacox.telephonypricing.dto.band;

import com.infomedia.abacox.telephonypricing.dto.bandgroup.BandGroupDto;
import com.infomedia.abacox.telephonypricing.dto.prefix.PrefixDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;

import java.math.BigDecimal;

public class BandDto extends ActivableDto {
    private Long id;
    private Long prefixId;
    private String name;
    private BigDecimal value;
    private boolean vatIncluded;  // This remains primitive
    private Integer minDistance;
    private Integer maxDistance;
    private Long bandGroupId;
    private PrefixDto prefix;
    private BandGroupDto bandGroup;
}