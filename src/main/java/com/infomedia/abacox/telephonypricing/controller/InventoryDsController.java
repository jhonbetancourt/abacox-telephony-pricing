package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.entity.InventoryDs;
import com.infomedia.abacox.telephonypricing.dto.inventoryds.CreateInventoryDs;
import com.infomedia.abacox.telephonypricing.dto.inventoryds.InventoryDsDto;
import com.infomedia.abacox.telephonypricing.dto.inventoryds.UpdateInventoryDs;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.ExportRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.PageableRequest;

import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.security.permissions.Permissions;
import com.infomedia.abacox.telephonypricing.service.InventoryDsService;
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
@Tag(name = "InventoryDs", description = "InventoryDs API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/inventoryDs")
public class InventoryDsController {

    private final InventoryDsService inventoryDsService;
    private final ModelConverter modelConverter;

    @RequiresPermission(Permissions.INVENTORY_READ)
    @Operation(summary = "List inventory DS entries")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<InventoryDsDto> find(@Parameter(hidden = true) @Filter Specification<InventoryDs> spec,
            @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(inventoryDsService.find(spec, pageable), InventoryDsDto.class);
    }

    @RequiresPermission(Permissions.INVENTORY_CREATE)
    @Operation(summary = "Create an inventory DS entry")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryDsDto create(@Valid @RequestBody CreateInventoryDs cDto) {
        return modelConverter.map(inventoryDsService.create(cDto), InventoryDsDto.class);
    }

    @RequiresPermission(Permissions.INVENTORY_UPDATE)
    @Operation(summary = "Update an inventory DS entry")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryDsDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateInventoryDs uDto) {
        return modelConverter.map(inventoryDsService.update(id, uDto), InventoryDsDto.class);
    }

    @RequiresPermission(Permissions.INVENTORY_READ)
    @Operation(summary = "Get inventory DS entry by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryDsDto get(@PathVariable("id") Long id) {
        return modelConverter.map(inventoryDsService.get(id), InventoryDsDto.class);
    }

    @RequiresPermission(Permissions.INVENTORY_READ)
    @Operation(summary = "Export inventory DS entries to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<InventoryDs> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {
        StreamingResponseBody body = out ->
            inventoryDsService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=inventory_suppliers.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}
