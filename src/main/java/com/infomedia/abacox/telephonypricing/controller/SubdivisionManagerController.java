package com.infomedia.abacox.telephonypricing.controller;


import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.subdivisionmanager.SubdivisionManagerDto;
import com.infomedia.abacox.telephonypricing.dto.subdivisionmanager.CreateSubdivisionManager;
import com.infomedia.abacox.telephonypricing.dto.subdivisionmanager.UpdateSubdivisionManager;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.SubdivisionManager;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.service.SubdivisionManagerService;
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
@Tag(name = "SubdivisionManager", description = "Subdivision Manager API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/subdivisionManager")
public class SubdivisionManagerController {

    private final SubdivisionManagerService subdivisionManagerService;
    private final ModelConverter modelConverter;

    @RequiresPermission("subdivision:read")
    @Operation(summary = "List subdivision managers")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<SubdivisionManagerDto> find(@Parameter(hidden = true) @Filter Specification<SubdivisionManager> spec
            , @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(subdivisionManagerService.find(spec, pageable), SubdivisionManagerDto.class);
    }

    @RequiresPermission("subdivision:create")
    @Operation(summary = "Create a subdivision manager")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SubdivisionManagerDto create(@Valid @RequestBody CreateSubdivisionManager createSubdivisionManager) {
        return modelConverter.map(subdivisionManagerService.create(createSubdivisionManager), SubdivisionManagerDto.class);
    }

    @RequiresPermission("subdivision:update")
    @Operation(summary = "Update a subdivision manager")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SubdivisionManagerDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateSubdivisionManager uDto) {
        return modelConverter.map(subdivisionManagerService.update(id, uDto), SubdivisionManagerDto.class);
    }

    @RequiresPermission("subdivision:update")
    @Operation(summary = "Change subdivision manager activation status")
    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SubdivisionManagerDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(subdivisionManagerService.changeActivation(id, activationDto.getActive()), SubdivisionManagerDto.class);
    }

    @RequiresPermission("subdivision:read")
    @Operation(summary = "Get subdivision manager by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SubdivisionManagerDto get(@PathVariable("id") Long id) {
        return modelConverter.map(subdivisionManagerService.get(id), SubdivisionManagerDto.class);
    }

    @RequiresPermission("subdivision:read")
    @Operation(summary = "Export subdivision managers to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<SubdivisionManager> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {


        StreamingResponseBody body = out ->
            subdivisionManagerService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=subdivision_managers.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}