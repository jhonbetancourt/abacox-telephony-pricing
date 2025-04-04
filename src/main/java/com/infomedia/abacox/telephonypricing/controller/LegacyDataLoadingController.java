package com.infomedia.abacox.telephonypricing.controller;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.infomedia.abacox.telephonypricing.dto.callrecord.CallRecordLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.commlocation.CommLocationLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.costcenter.CostCenterLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.employee.EmployeeLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.indicator.IndicatorLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.jobposition.JobPositionLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.operator.OperatorLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.planttype.PlantTypeLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.subdivision.SubdivisionLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.TelephonyTypeLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.band.BandLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.bandgroup.BandGroupLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.bandindicator.BandIndicatorLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.callcategory.CallCategoryLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.city.CityLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.company.CompanyLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.contact.ContactLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.origincountry.OriginCountryLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.prefix.PrefixLegacyMapping;
import com.infomedia.abacox.telephonypricing.dto.series.SeriesLegacyMapping;
import com.infomedia.abacox.telephonypricing.service.LegacyDataLoadingService;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

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
    private final ObjectMapper objectMapper;

    @PostMapping(value = "csv/jobPosition", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvJobPosition(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = JobPositionLegacyMapping.class) String mappingJson) throws IOException {
        JobPositionLegacyMapping mapping = objectMapper.readValue(mappingJson, JobPositionLegacyMapping.class);
        legacyDataLoadingService.loadJobPositionData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/costCenter", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvCostCenter(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = CostCenterLegacyMapping.class) String mappingJson) throws IOException {
        CostCenterLegacyMapping mapping = objectMapper.readValue(mappingJson, CostCenterLegacyMapping.class);
        legacyDataLoadingService.loadCostCenterData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/subdivision", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvSubdivision(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = SubdivisionLegacyMapping.class) String mappingJson) throws IOException {
        SubdivisionLegacyMapping mapping = objectMapper.readValue(mappingJson, SubdivisionLegacyMapping.class);
        legacyDataLoadingService.loadSubdivisionData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/plantType", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvPlantType(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = PlantTypeLegacyMapping.class) String mappingJson) throws IOException {
        PlantTypeLegacyMapping mapping = objectMapper.readValue(mappingJson, PlantTypeLegacyMapping.class);
        legacyDataLoadingService.loadPlantTypeData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/commLocation", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvCommLocation(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = CommLocationLegacyMapping.class) String mappingJson) throws IOException {
        CommLocationLegacyMapping mapping = objectMapper.readValue(mappingJson, CommLocationLegacyMapping.class);
        legacyDataLoadingService.loadCommLocationData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/employee", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvEmployee(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = EmployeeLegacyMapping.class) String mappingJson) throws IOException {
        EmployeeLegacyMapping mapping = objectMapper.readValue(mappingJson, EmployeeLegacyMapping.class);
        legacyDataLoadingService.loadEmployeeData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/callRecord", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvCallRecord(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = CallRecordLegacyMapping.class) String mappingJson) throws IOException {
        CallRecordLegacyMapping mapping = objectMapper.readValue(mappingJson, CallRecordLegacyMapping.class);
        legacyDataLoadingService.loadCallRecordData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/telephonyType", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvTelephonyType(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = TelephonyTypeLegacyMapping.class) String mappingJson) throws IOException {
        TelephonyTypeLegacyMapping mapping = objectMapper.readValue(mappingJson, TelephonyTypeLegacyMapping.class);
        legacyDataLoadingService.loadTelephonyTypeData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/indicator", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvIndicator(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = IndicatorLegacyMapping.class) String mappingJson) throws IOException {
        IndicatorLegacyMapping mapping = objectMapper.readValue(mappingJson, IndicatorLegacyMapping.class);
        legacyDataLoadingService.loadIndicatorData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/operator", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvOperator(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = OperatorLegacyMapping.class) String mappingJson) throws IOException {
        OperatorLegacyMapping mapping = objectMapper.readValue(mappingJson, OperatorLegacyMapping.class);
        legacyDataLoadingService.loadOperatorData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/band", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvBand(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = BandLegacyMapping.class) String mappingJson) throws IOException {
        BandLegacyMapping mapping = objectMapper.readValue(mappingJson, BandLegacyMapping.class);
        legacyDataLoadingService.loadBandData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/bandGroup", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvBandGroup(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = BandGroupLegacyMapping.class) String mappingJson) throws IOException {
        BandGroupLegacyMapping mapping = objectMapper.readValue(mappingJson, BandGroupLegacyMapping.class);
        legacyDataLoadingService.loadBandGroupData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/bandIndicator", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvBandIndicator(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = BandIndicatorLegacyMapping.class) String mappingJson) throws IOException {
        BandIndicatorLegacyMapping mapping = objectMapper.readValue(mappingJson, BandIndicatorLegacyMapping.class);
        legacyDataLoadingService.loadBandIndicatorData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/callCategory", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvCallCategory(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = CallCategoryLegacyMapping.class) String mappingJson) throws IOException {
        CallCategoryLegacyMapping mapping = objectMapper.readValue(mappingJson, CallCategoryLegacyMapping.class);
        legacyDataLoadingService.loadCallCategoryData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/city", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvCity(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = CityLegacyMapping.class) String mappingJson) throws IOException {
        CityLegacyMapping mapping = objectMapper.readValue(mappingJson, CityLegacyMapping.class);
        legacyDataLoadingService.loadCityData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/company", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvCompany(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = CompanyLegacyMapping.class) String mappingJson) throws IOException {
        CompanyLegacyMapping mapping = objectMapper.readValue(mappingJson, CompanyLegacyMapping.class);
        legacyDataLoadingService.loadCompanyData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/contact", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvContact(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = ContactLegacyMapping.class) String mappingJson) throws IOException {
        ContactLegacyMapping mapping = objectMapper.readValue(mappingJson, ContactLegacyMapping.class);
        legacyDataLoadingService.loadContactData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/originCountry", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvOriginCountry(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = OriginCountryLegacyMapping.class) String mappingJson) throws IOException {
        OriginCountryLegacyMapping mapping = objectMapper.readValue(mappingJson, OriginCountryLegacyMapping.class);
        legacyDataLoadingService.loadOriginCountryData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/prefix", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvPrefix(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = PrefixLegacyMapping.class) String mappingJson) throws IOException {
        PrefixLegacyMapping mapping = objectMapper.readValue(mappingJson, PrefixLegacyMapping.class);
        legacyDataLoadingService.loadPrefixData(file.getInputStream(), mapping);
    }

    @PostMapping(value = "csv/series", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void csvSeries(@RequestPart("file") MultipartFile file
            , @RequestPart("mapping") @Schema(implementation = SeriesLegacyMapping.class) String mappingJson) throws IOException {
        SeriesLegacyMapping mapping = objectMapper.readValue(mappingJson, SeriesLegacyMapping.class);
        legacyDataLoadingService.loadSeriesData(file.getInputStream(), mapping);
    }
}