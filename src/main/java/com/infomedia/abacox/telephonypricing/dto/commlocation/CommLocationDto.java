package com.infomedia.abacox.telephonypricing.dto.commlocation;

import com.infomedia.abacox.telephonypricing.dto.bandgroup.BandGroupDto;
import com.infomedia.abacox.telephonypricing.dto.planttype.PlantTypeDto;
import com.infomedia.abacox.telephonypricing.dto.indicator.IndicatorDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for {@link CommunicationLocation}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CommLocationDto extends ActivableDto {
    private Long id;
    private String directory;
    private Long plantTypeId;
    private PlantTypeDto plantType;
    private String serial;
    private Long indicatorId;
    private IndicatorDto indicator;
    private String pbxPrefix;
    private String address;
    private LocalDateTime captureDate;
    private Integer cdrCount;
    private String fileName;
    private Long bandGroupId;
    private BandGroupDto bandGroup;
    private Long headerId;
    private Integer withoutCaptures;
}