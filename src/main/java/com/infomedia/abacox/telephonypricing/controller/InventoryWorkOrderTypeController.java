package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.entity.InventoryWorkOrderType;
import com.infomedia.abacox.telephonypricing.dto.inventoryworkordertype.CreateInventoryWorkOrderType;
import com.infomedia.abacox.telephonypricing.dto.inventoryworkordertype.InventoryWorkOrderTypeDto;
import com.infomedia.abacox.telephonypricing.dto.inventoryworkordertype.UpdateInventoryWorkOrderType;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;

import com.infomedia.abacox.telephonypricing.service.InventoryWorkOrderTypeService;
import com.turkraft.springfilter.boot.Filter;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<InventoryWorkOrderTypeDto> find(@Parameter(hidden = true) @Filter Specification<InventoryWorkOrderType> spec,
            @Parameter(hidden = true) Pageable pageable,
            @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapSlice(inventoryWorkOrderTypeService.find(spec, pageable), InventoryWorkOrderTypeDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryWorkOrderTypeDto create(@Valid @RequestBody CreateInventoryWorkOrderType cDto) {
        return modelConverter.map(inventoryWorkOrderTypeService.create(cDto), InventoryWorkOrderTypeDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryWorkOrderTypeDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateInventoryWorkOrderType uDto) {
        return modelConverter.map(inventoryWorkOrderTypeService.update(id, uDto), InventoryWorkOrderTypeDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryWorkOrderTypeDto get(@PathVariable("id") Long id) {
        return modelConverter.map(inventoryWorkOrderTypeService.get(id), InventoryWorkOrderTypeDto.class);
    }

    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcel(@Parameter(hidden = true) @Filter Specification<InventoryWorkOrderType> spec,
            @Parameter(hidden = true) Pageable pageable,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExcelRequest excelRequest) {
        ByteArrayResource resource = inventoryWorkOrderTypeService.exportExcel(spec, pageable, excelRequest.toExcelGeneratorBuilder());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=inventory_work_order_types.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
    }
}
