package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.component.export.excel.ExportParamProcessor;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.telephonytypeconfig.TelephonyTypeConfigDto;
import com.infomedia.abacox.telephonypricing.dto.telephonytypeconfig.CreateTelephonyTypeConfig;
import com.infomedia.abacox.telephonypricing.dto.telephonytypeconfig.UpdateTelephonyTypeConfig;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.TelephonyTypeConfig;
import com.infomedia.abacox.telephonypricing.service.TelephonyTypeConfigService;
import com.turkraft.springfilter.boot.Filter;
import io.swagger.v3.oas.annotations.Parameter;
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
@Tag(name = "TelephonyTypeConfig", description = "Telephony Type Configuration API")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/telephonyTypeConfig")
public class TelephonyTypeConfigController {

    private final TelephonyTypeConfigService telephonyTypeConfigService;
    private final ModelConverter modelConverter;
    private final ExportParamProcessor exportParamProcessor;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<TelephonyTypeConfigDto> find(@Parameter(hidden = true) @Filter Specification<TelephonyTypeConfig> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapPage(telephonyTypeConfigService.find(spec, pageable), TelephonyTypeConfigDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public TelephonyTypeConfigDto create(@Valid @RequestBody CreateTelephonyTypeConfig createTelephonyTypeConfig) {
        return modelConverter.map(telephonyTypeConfigService.create(createTelephonyTypeConfig), TelephonyTypeConfigDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public TelephonyTypeConfigDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateTelephonyTypeConfig uDto) {
        return modelConverter.map(telephonyTypeConfigService.update(id, uDto), TelephonyTypeConfigDto.class);
    }

    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TelephonyTypeConfigDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(telephonyTypeConfigService.changeActivation(id, activationDto.getActive()), TelephonyTypeConfigDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TelephonyTypeConfigDto get(@PathVariable("id") Long id) {
        return modelConverter.map(telephonyTypeConfigService.get(id), TelephonyTypeConfigDto.class);
    }

    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcel(@Parameter(hidden = true) @Filter Specification<TelephonyTypeConfig> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort
            , @RequestParam(required = false) String alternativeHeaders
            , @RequestParam(required = false) String excludeColumns
            , @RequestParam(required = false) String includeColumns
            , @RequestParam(required = false) String valueReplacements) {

        ExcelGeneratorBuilder excelGeneratorBuilder = exportParamProcessor.base64ParamsToExcelGeneratorBuilder(
                alternativeHeaders, excludeColumns, includeColumns, valueReplacements);


        ByteArrayResource resource = telephonyTypeConfigService.exportExcel(spec, pageable, excelGeneratorBuilder);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=telephony_type_configs.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
    }
}