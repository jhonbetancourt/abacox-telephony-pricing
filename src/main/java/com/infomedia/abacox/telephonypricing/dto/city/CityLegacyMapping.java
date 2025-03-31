package com.infomedia.abacox.telephonypricing.dto.city;

import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableLegacyMapping;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CityLegacyMapping extends ActivableLegacyMapping {
    private String id;
    private String department;
    private String classification;
    private String municipality;
    private String municipalCapital;
    private String latitude;
    private String longitude;
    private String altitude;
    private String northCoordinate;
    private String eastCoordinate;
    private String origin;
}