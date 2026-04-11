package com.infomedia.abacox.telephonypricing.controller;


import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.costcenter.CostCenterDto;
import com.infomedia.abacox.telephonypricing.dto.costcenter.CreateCostCenter;
import com.infomedia.abacox.telephonypricing.dto.costcenter.UpdateCostCenter;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.CostCenter;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.security.permissions.Permissions;
import com.infomedia.abacox.telephonypricing.service.CostCenterService;
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
@Tag(name = "CostCenter", description = "CostCenter API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/costCenter")
public class CostCenterController {

    private final CostCenterService costCenterService;
    private final ModelConverter modelConverter;

    @RequiresPermission(Permissions.COST_CENTER_READ)
    @Operation(summary = "List cost centers")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<CostCenterDto> find(@Parameter(hidden = true) @Filter Specification<CostCenter> spec
            , @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(costCenterService.find(spec, pageable), CostCenterDto.class);
    }

    @RequiresPermission(Permissions.COST_CENTER_CREATE)
    @Operation(summary = "Create a cost center")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CostCenterDto create(@Valid @RequestBody CreateCostCenter createCostCenter) {
        return modelConverter.map(costCenterService.create(createCostCenter), CostCenterDto.class);
    }

    @RequiresPermission(Permissions.COST_CENTER_UPDATE)
    @Operation(summary = "Update a cost center")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CostCenterDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateCostCenter uDto) {
        return modelConverter.map(costCenterService.update(id, uDto), CostCenterDto.class);
    }

    @RequiresPermission(Permissions.COST_CENTER_READ)
    @Operation(summary = "Get cost center by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    private CostCenterDto get(@PathVariable("id") Long id) {
        return modelConverter.map(costCenterService.get(id), CostCenterDto.class);
    }

    @RequiresPermission(Permissions.COST_CENTER_UPDATE)
    @Operation(summary = "Change cost center activation status")
    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CostCenterDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(costCenterService.changeActivation(id, activationDto.getActive()), CostCenterDto.class);
    }

    @RequiresPermission(Permissions.COST_CENTER_READ)
    @Operation(summary = "Export cost centers to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<CostCenter> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {

        
        StreamingResponseBody body = out ->
            costCenterService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=cost_centers.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}