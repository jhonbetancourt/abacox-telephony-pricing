package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.dto.callrecord.CallRecordLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.commlocation.CommLocationLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.costcenter.CostCenterLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.employee.EmployeeLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.generic.LegacyLoadDto;
import com.infomedia.abacox.telephonypricing.dto.jobposition.JobPositionLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.planttype.PlantTypeLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.subdivision.SubdivisionLegacyMapping;
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
    public void csvJobPosition(@Valid @RequestBody LegacyLoadDto<JobPositionLegacyMapping> legacyLoadDto) {
        ByteArrayInputStream csvInputStream = new ByteArrayInputStream(Base64.getDecoder().decode(legacyLoadDto.getCsvFileBase64()));
        legacyDataLoadingService.loadJobPositionData(csvInputStream, legacyLoadDto.getLegacyMapping());
    }

    @PostMapping(value = "csv/costCenter", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public void csvCostCenter(@Valid @RequestBody LegacyLoadDto<CostCenterLegacyMapping> legacyLoadDto) {
        ByteArrayInputStream csvInputStream = new ByteArrayInputStream(Base64.getDecoder().decode(legacyLoadDto.getCsvFileBase64()));
        legacyDataLoadingService.loadCostCenterData(csvInputStream, legacyLoadDto.getLegacyMapping());
    }
    
    @PostMapping(value = "csv/subdivision", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public void csvSubdivision(@Valid @RequestBody LegacyLoadDto<SubdivisionLegacyMapping> legacyLoadDto) {
        ByteArrayInputStream csvInputStream = new ByteArrayInputStream(Base64.getDecoder().decode(legacyLoadDto.getCsvFileBase64()));
        legacyDataLoadingService.loadSubdivisionData(csvInputStream, legacyLoadDto.getLegacyMapping());
    }

    @PostMapping(value = "csv/plantType", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public void csvPlantType(@Valid @RequestBody LegacyLoadDto<PlantTypeLegacyMapping> legacyLoadDto) {
        ByteArrayInputStream csvInputStream = new ByteArrayInputStream(Base64.getDecoder().decode(legacyLoadDto.getCsvFileBase64()));
        legacyDataLoadingService.loadPlantTypeData(csvInputStream, legacyLoadDto.getLegacyMapping());
    }

    @PostMapping(value = "csv/commLocation", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public void csvCommLocation(@Valid @RequestBody LegacyLoadDto<CommLocationLegacyMapping> legacyLoadDto) {
        ByteArrayInputStream csvInputStream = new ByteArrayInputStream(Base64.getDecoder().decode(legacyLoadDto.getCsvFileBase64()));
        legacyDataLoadingService.loadCommLocationData(csvInputStream, legacyLoadDto.getLegacyMapping());
    }

    @PostMapping(value = "csv/employee", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public void csvEmployee(@Valid @RequestBody LegacyLoadDto<EmployeeLegacyMapping> legacyLoadDto) {
        ByteArrayInputStream csvInputStream = new ByteArrayInputStream(Base64.getDecoder().decode(legacyLoadDto.getCsvFileBase64()));
        legacyDataLoadingService.loadEmployeeData(csvInputStream, legacyLoadDto.getLegacyMapping());
    }

    @PostMapping(value = "csv/callRecord", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public void csvCallRecord(@Valid @RequestBody LegacyLoadDto<CallRecordLegacyMapping> legacyLoadDto) {
        ByteArrayInputStream csvInputStream = new ByteArrayInputStream(Base64.getDecoder().decode(legacyLoadDto.getCsvFileBase64()));
        legacyDataLoadingService.loadCallRecordData(csvInputStream, legacyLoadDto.getLegacyMapping());
    }
}