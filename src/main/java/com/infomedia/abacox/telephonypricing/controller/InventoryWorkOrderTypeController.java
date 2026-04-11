package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.entity.InventoryWorkOrderType;
import com.infomedia.abacox.telephonypricing.dto.inventoryworkordertype.CreateInventoryWorkOrderType;
import com.infomedia.abacox.telephonypricing.dto.inventoryworkordertype.InventoryWorkOrderTypeDto;
import com.infomedia.abacox.telephonypricing.dto.inventoryworkordertype.UpdateInventoryWorkOrderType;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.ExportRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.PageableRequest;

import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.security.permissions.Permissions;
import com.infomedia.abacox.telephonypricing.service.InventoryWorkOrderTypeService;
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
@Tag(name = "InventoryWorkOrderType", description = "InventoryWorkOrderType API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/inventoryWorkOrderType")
public class InventoryWorkOrderTypeController {

    private final InventoryWorkOrderTypeService inventoryWorkOrderTypeService;
    private final ModelConverter modelConverter;

    @RequiresPermission(Permissions.INVENTORY_READ)
    @Operation(summary = "List inventory work order types")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<InventoryWorkOrderTypeDto> find(@Parameter(hidden = true) @Filter Specification<InventoryWorkOrderType> spec,
            @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(inventoryWorkOrderTypeService.find(spec, pageable), InventoryWorkOrderTypeDto.class);
    }

    @RequiresPermission(Permissions.INVENTORY_CREATE)
    @Operation(summary = "Create an inventory work order type")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryWorkOrderTypeDto create(@Valid @RequestBody CreateInventoryWorkOrderType cDto) {
        return modelConverter.map(inventoryWorkOrderTypeService.create(cDto), InventoryWorkOrderTypeDto.class);
    }

    @RequiresPermission(Permissions.INVENTORY_UPDATE)
    @Operation(summary = "Update an inventory work order type")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryWorkOrderTypeDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateInventoryWorkOrderType uDto) {
        return modelConverter.map(inventoryWorkOrderTypeService.update(id, uDto), InventoryWorkOrderTypeDto.class);
    }

    @RequiresPermission(Permissions.INVENTORY_READ)
    @Operation(summary = "Get inventory work order type by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryWorkOrderTypeDto get(@PathVariable("id") Long id) {
        return modelConverter.map(inventoryWorkOrderTypeService.get(id), InventoryWorkOrderTypeDto.class);
    }

    @RequiresPermission(Permissions.INVENTORY_READ)
    @Operation(summary = "Export inventory work order types to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<InventoryWorkOrderType> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {
        StreamingResponseBody body = out ->
            inventoryWorkOrderTypeService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=inventory_work_order_types.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}
