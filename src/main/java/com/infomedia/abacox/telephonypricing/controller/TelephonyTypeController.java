package com.infomedia.abacox.telephonypricing.controller;


import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.TelephonyTypeDto;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.CreateTelephonyType;
import com.infomedia.abacox.telephonypricing.dto.telephonytype.UpdateTelephonyType;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.TelephonyType;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.security.permissions.Permissions;
import com.infomedia.abacox.telephonypricing.service.TelephonyTypeService;
import com.turkraft.springfilter.boot.Filter;
import io.swagger.v3.oas.annotations.Operation;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.ExportRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.PageableRequest;
import io.swagger.v3.oas.annotations.Parameter;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RequiredArgsConstructor
@RestController
@Tag(name = "TelephonyType", description = "TelephonyType API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/telephonyType")
public class TelephonyTypeController {

    private final TelephonyTypeService telephonyTypeService;
    private final ModelConverter modelConverter;

    @RequiresPermission(Permissions.TELEPHONY_CONFIG_READ)
    @Operation(summary = "List telephony types")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<TelephonyTypeDto> find(@Parameter(hidden = true) @Filter Specification<TelephonyType> spec
            , @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(telephonyTypeService.find(spec, pageable), TelephonyTypeDto.class);
    }

    @RequiresPermission(Permissions.TELEPHONY_CONFIG_CREATE)
    @Operation(summary = "Create a telephony type")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public TelephonyTypeDto create(@Valid @RequestBody CreateTelephonyType createTelephonyType) {
        return modelConverter.map(telephonyTypeService.create(createTelephonyType), TelephonyTypeDto.class);
    }

    @RequiresPermission(Permissions.TELEPHONY_CONFIG_UPDATE)
    @Operation(summary = "Update a telephony type")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public TelephonyTypeDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateTelephonyType uDto) {
        return modelConverter.map(telephonyTypeService.update(id, uDto), TelephonyTypeDto.class);
    }

    @RequiresPermission(Permissions.TELEPHONY_CONFIG_READ)
    @Operation(summary = "Get telephony type by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TelephonyTypeDto get(@PathVariable("id") Long id) {
        return modelConverter.map(telephonyTypeService.get(id), TelephonyTypeDto.class);
    }

    @RequiresPermission(Permissions.TELEPHONY_CONFIG_UPDATE)
    @Operation(summary = "Change telephony type activation status")
    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TelephonyTypeDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(telephonyTypeService.changeActivation(id, activationDto.getActive()), TelephonyTypeDto.class);
    }

    @RequiresPermission(Permissions.TELEPHONY_CONFIG_READ)
    @Operation(summary = "Export telephony types to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<TelephonyType> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {

        
        StreamingResponseBody body = out ->
            telephonyTypeService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=telephony_types.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}