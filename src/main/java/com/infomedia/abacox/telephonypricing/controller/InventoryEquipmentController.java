package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.entity.InventoryEquipment;
import com.infomedia.abacox.telephonypricing.dto.inventoryequipment.CreateInventoryEquipment;
import com.infomedia.abacox.telephonypricing.dto.inventoryequipment.InventoryEquipmentDto;
import com.infomedia.abacox.telephonypricing.dto.inventoryequipment.UpdateInventoryEquipment;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.ExportRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.PageableRequest;

import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.security.permissions.Permissions;
import com.infomedia.abacox.telephonypricing.service.InventoryEquipmentService;
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
@Tag(name = "InventoryEquipment", description = "InventoryEquipment API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/inventoryEquipment")
public class InventoryEquipmentController {

    private final InventoryEquipmentService inventoryEquipmentService;
    private final ModelConverter modelConverter;

    @RequiresPermission(Permissions.INVENTORY_READ)
    @Operation(summary = "List inventory equipment")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<InventoryEquipmentDto> find(@Parameter(hidden = true) @Filter Specification<InventoryEquipment> spec,
            @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(inventoryEquipmentService.find(spec, pageable), InventoryEquipmentDto.class);
    }

    @RequiresPermission(Permissions.INVENTORY_CREATE)
    @Operation(summary = "Create inventory equipment")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryEquipmentDto create(@Valid @RequestBody CreateInventoryEquipment cDto) {
        return modelConverter.map(inventoryEquipmentService.create(cDto), InventoryEquipmentDto.class);
    }

    @RequiresPermission(Permissions.INVENTORY_UPDATE)
    @Operation(summary = "Update inventory equipment")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryEquipmentDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateInventoryEquipment uDto) {
        return modelConverter.map(inventoryEquipmentService.update(id, uDto), InventoryEquipmentDto.class);
    }

    @RequiresPermission(Permissions.INVENTORY_READ)
    @Operation(summary = "Get inventory equipment by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryEquipmentDto get(@PathVariable("id") Long id) {
        return modelConverter.map(inventoryEquipmentService.get(id), InventoryEquipmentDto.class);
    }

    @RequiresPermission(Permissions.INVENTORY_READ)
    @Operation(summary = "Export inventory equipment to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<InventoryEquipment> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {
        StreamingResponseBody body = out ->
            inventoryEquipmentService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=inventory_equipment.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}
