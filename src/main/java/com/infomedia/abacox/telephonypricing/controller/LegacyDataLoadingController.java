package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.dto.costcenter.CostCenterLegacyLoad;
import com.infomedia.abacox.telephonypricing.dto.subdivision.SubdivisionLegacyLoad;
import com.infomedia.abacox.telephonypricing.service.LegacyDataLoadingService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.util.Base64;

@RequiredArgsConstructor
@RestController
@Tag(name = "Legacy Data Loading", description = "Legacy Data Loading Controller")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/legacyDataLoading")
public class LegacyDataLoadingController {

    private final LegacyDataLoadingService legacyDataLoadingService;

    @PostMapping(value = "csv/jobPosition", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public void csvJobPosition(@Valid @RequestBody SubdivisionLegacyLoad legacyLoadDto) {
        ByteArrayInputStream csvInputStream = new ByteArrayInputStream(Base64.getDecoder().decode(legacyLoadDto.getCsvFileBase64()));
        legacyDataLoadingService.loadJobPositionData(csvInputStream, legacyLoadDto.getLegacyMapping());
    }

    @PostMapping(value = "csv/costCenter", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public void csvCostCenter(@Valid @RequestBody CostCenterLegacyLoad legacyLoadDto) {
        ByteArrayInputStream csvInputStream = new ByteArrayInputStream(Base64.getDecoder().decode(legacyLoadDto.getCsvFileBase64()));
        legacyDataLoadingService.loadCostCenterData(csvInputStream, legacyLoadDto.getLegacyMapping());
    }
    
    @PostMapping(value = "csv/subdivision", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public void csvSubdivision(@Valid @RequestBody SubdivisionLegacyLoad legacyLoadDto) {
        ByteArrayInputStream csvInputStream = new ByteArrayInputStream(Base64.getDecoder().decode(legacyLoadDto.getCsvFileBase64()));
        legacyDataLoadingService.loadSubdivisionData(csvInputStream, legacyLoadDto.getLegacyMapping());
    }
}