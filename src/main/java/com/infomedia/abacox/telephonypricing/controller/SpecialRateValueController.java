package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.specialratevalue.SpecialRateValueDto;
import com.infomedia.abacox.telephonypricing.dto.specialratevalue.CreateSpecialRateValue;
import com.infomedia.abacox.telephonypricing.dto.specialratevalue.UpdateSpecialRateValue;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.SpecialRateValue;
import com.infomedia.abacox.telephonypricing.service.SpecialRateValueService;
import com.turkraft.springfilter.boot.Filter;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;
import io.swagger.v3.oas.annotations.Parameter;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@Tag(name = "SpecialRateValue", description = "Special Rate Value API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/specialRateValue")
public class SpecialRateValueController {

    private final SpecialRateValueService specialRateValueService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<SpecialRateValueDto> find(@Parameter(hidden = true) @Filter Specification<SpecialRateValue> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapPage(specialRateValueService.find(spec, pageable), SpecialRateValueDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialRateValueDto create(@Valid @RequestBody CreateSpecialRateValue createSpecialRateValue) {
        return modelConverter.map(specialRateValueService.create(createSpecialRateValue), SpecialRateValueDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialRateValueDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateSpecialRateValue uDto) {
        return modelConverter.map(specialRateValueService.update(id, uDto), SpecialRateValueDto.class);
    }

    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialRateValueDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(specialRateValueService.changeActivation(id, activationDto.getActive()), SpecialRateValueDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecialRateValueDto get(@PathVariable("id") Long id) {
        return modelConverter.map(specialRateValueService.get(id), SpecialRateValueDto.class);
    }

    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcel(@Parameter(hidden = true) @Filter Specification<SpecialRateValue> spec
            , @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject ExcelRequest excelRequest) {

        
        ByteArrayResource resource = specialRateValueService.exportExcel(spec, pageable, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=special_rate_values.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
    }
}