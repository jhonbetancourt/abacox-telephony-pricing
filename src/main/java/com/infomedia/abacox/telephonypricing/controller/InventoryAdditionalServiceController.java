package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.entity.InventoryAdditionalService;
import com.infomedia.abacox.telephonypricing.dto.inventoryadditionalservice.CreateInventoryAdditionalService;
import com.infomedia.abacox.telephonypricing.dto.inventoryadditionalservice.InventoryAdditionalServiceDto;
import com.infomedia.abacox.telephonypricing.dto.inventoryadditionalservice.UpdateInventoryAdditionalService;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.ExportRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.PageableRequest;

import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.security.permissions.Permissions;
import com.infomedia.abacox.telephonypricing.service.InventoryAdditionalServiceService;
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
@Tag(name = "InventoryAdditionalService", description = "InventoryAdditionalService API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/inventoryAdditionalService")
public class InventoryAdditionalServiceController {

    private final InventoryAdditionalServiceService inventoryAdditionalServiceService;
    private final ModelConverter modelConverter;

    @RequiresPermission(Permissions.INVENTORY_READ)
    @Operation(summary = "List inventory additional services")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<InventoryAdditionalServiceDto> find(@Parameter(hidden = true) @Filter Specification<InventoryAdditionalService> spec,
            @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(inventoryAdditionalServiceService.find(spec, pageable), InventoryAdditionalServiceDto.class);
    }

    @RequiresPermission(Permissions.INVENTORY_CREATE)
    @Operation(summary = "Create an inventory additional service")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryAdditionalServiceDto create(@Valid @RequestBody CreateInventoryAdditionalService cDto) {
        return modelConverter.map(inventoryAdditionalServiceService.create(cDto), InventoryAdditionalServiceDto.class);
    }

    @RequiresPermission(Permissions.INVENTORY_UPDATE)
    @Operation(summary = "Update an inventory additional service")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryAdditionalServiceDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateInventoryAdditionalService uDto) {
        return modelConverter.map(inventoryAdditionalServiceService.update(id, uDto), InventoryAdditionalServiceDto.class);
    }

    @RequiresPermission(Permissions.INVENTORY_READ)
    @Operation(summary = "Get inventory additional service by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryAdditionalServiceDto get(@PathVariable("id") Long id) {
        return modelConverter.map(inventoryAdditionalServiceService.get(id), InventoryAdditionalServiceDto.class);
    }

    @RequiresPermission(Permissions.INVENTORY_READ)
    @Operation(summary = "Export inventory additional services to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<InventoryAdditionalService> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {
        StreamingResponseBody body = out ->
            inventoryAdditionalServiceService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=inventory_additional_services.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}
