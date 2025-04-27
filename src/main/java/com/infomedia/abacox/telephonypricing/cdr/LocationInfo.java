package com.infomedia.abacox.telephonypricing.cdr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationInfo {
    private Long comubicacionId;
    private Long mporigenId;
    private Long indicativoId;
    private String indicativoDptoPais;
    private String indicativoCiudad;
    private String comubicacionDirectorio;
    private String comubicacionPrefijopbx;
}