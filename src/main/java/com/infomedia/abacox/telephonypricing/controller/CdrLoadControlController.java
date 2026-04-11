package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.entity.CdrLoadControl;
import com.infomedia.abacox.telephonypricing.dto.cdrloadcontrol.CdrLoadControlDto;
import com.infomedia.abacox.telephonypricing.dto.cdrloadcontrol.CreateCdrLoadControl;
import com.infomedia.abacox.telephonypricing.dto.cdrloadcontrol.UpdateCdrLoadControl;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.ExportRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.PageableRequest;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.security.permissions.Permissions;
import com.infomedia.abacox.telephonypricing.service.CdrLoadControlService;
import com.turkraft.springfilter.boot.Filter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RequiredArgsConstructor
@RestController
@Tag(name = "CdrLoadControl", description = "CDR Load Control API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/cdrLoadControl")
public class CdrLoadControlController {

    private final CdrLoadControlService cdrLoadControlService;
    private final ModelConverter modelConverter;

    @RequiresPermission(Permissions.CDR_READ)
    @Operation(summary = "List CDR load control entries")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<CdrLoadControlDto> find(@Parameter(hidden = true) @Filter Specification<CdrLoadControl> spec,
            @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(cdrLoadControlService.find(spec, pageable), CdrLoadControlDto.class);
    }

    @RequiresPermission(Permissions.CDR_CREATE)
    @Operation(summary = "Create a CDR load control entry")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CdrLoadControlDto create(@Valid @RequestBody CreateCdrLoadControl cDto) {
        return modelConverter.map(cdrLoadControlService.create(cDto), CdrLoadControlDto.class);
    }

    @RequiresPermission(Permissions.CDR_UPDATE)
    @Operation(summary = "Update a CDR load control entry")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CdrLoadControlDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateCdrLoadControl uDto) {
        return modelConverter.map(cdrLoadControlService.update(id, uDto), CdrLoadControlDto.class);
    }

    @RequiresPermission(Permissions.CDR_READ)
    @Operation(summary = "Get CDR load control entry by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CdrLoadControlDto get(@PathVariable("id") Long id) {
        return modelConverter.map(cdrLoadControlService.get(id), CdrLoadControlDto.class);
    }

    @RequiresPermission(Permissions.CDR_UPDATE)
    @Operation(summary = "Change CDR load control activation status")
    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CdrLoadControlDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(cdrLoadControlService.changeActivation(id, activationDto.getActive()), CdrLoadControlDto.class);
    }

    @RequiresPermission(Permissions.CDR_READ)
    @Operation(summary = "Export CDR load control entries to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<CdrLoadControl> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {
        StreamingResponseBody body = out ->
            cdrLoadControlService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=cdr_load_control.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}
