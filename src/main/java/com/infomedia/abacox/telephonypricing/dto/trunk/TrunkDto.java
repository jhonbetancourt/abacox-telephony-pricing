package com.infomedia.abacox.telephonypricing.dto.trunk;

import com.infomedia.abacox.telephonypricing.dto.commlocation.CommLocationDto;
import com.infomedia.abacox.telephonypricing.dto.operator.OperatorDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TrunkDto extends ActivableDto {
    private Long id;
    private Long commLocationId;
    private String description;
    private String trunk;
    private Long operatorId;
    private boolean noPbxPrefix;
    private Integer channels;
    private CommLocationDto commLocation;
    private OperatorDto operator;
}