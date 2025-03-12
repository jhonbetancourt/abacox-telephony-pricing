package com.infomedia.abacox.telephonypricing.dto.city;

import com.infomedia.abacox.telephonypricing.entity.City;
import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * DTO for {@link City}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CityDto extends AuditedEntity {
    private Long id;
    private String department;
    private String classification;
    private String municipality;
    private String municipalCapital;
    private String latitude;
    private String longitude;
    private Integer altitude;
    private Integer northCoordinate;
    private Integer eastCoordinate;
    private String origin;
}