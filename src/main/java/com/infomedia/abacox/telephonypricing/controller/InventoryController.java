package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.entity.Inventory;
import com.infomedia.abacox.telephonypricing.dto.inventory.CreateInventory;
import com.infomedia.abacox.telephonypricing.dto.inventory.InventoryDto;
import com.infomedia.abacox.telephonypricing.dto.inventory.UpdateInventory;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;

import com.infomedia.abacox.telephonypricing.service.InventoryService;
import com.turkraft.springfilter.boot.Filter;
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
@Tag(name = "Inventory", description = "Inventory API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<InventoryDto> find(@Parameter(hidden = true) @Filter Specification<Inventory> spec,
            @Parameter(hidden = true) Pageable pageable,
            @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapSlice(inventoryService.find(spec, pageable), InventoryDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryDto create(@Valid @RequestBody CreateInventory cDto) {
        return modelConverter.map(inventoryService.create(cDto), InventoryDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateInventory uDto) {
        return modelConverter.map(inventoryService.update(id, uDto), InventoryDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryDto get(@PathVariable("id") Long id) {
        return modelConverter.map(inventoryService.get(id), InventoryDto.class);
    }

    @PatchMapping(value = "/retire/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> retire(@PathVariable("id") Long id) {
        inventoryService.retire(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<Inventory> spec,
            @Parameter(hidden = true) Pageable pageable,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExcelRequest excelRequest) {
        StreamingResponseBody body = out ->
            inventoryService.exportExcelStreaming(spec, pageable, out, excelRequest.toExcelGeneratorBuilder());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=inventory.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}
