package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.entity.InventoryAdditionalService;
import com.infomedia.abacox.telephonypricing.dto.inventoryadditionalservice.CreateInventoryAdditionalService;
import com.infomedia.abacox.telephonypricing.dto.inventoryadditionalservice.InventoryAdditionalServiceDto;
import com.infomedia.abacox.telephonypricing.dto.inventoryadditionalservice.UpdateInventoryAdditionalService;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;

import com.infomedia.abacox.telephonypricing.service.InventoryAdditionalServiceService;
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
@Tag(name = "InventoryAdditionalService", description = "InventoryAdditionalService API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/inventoryAdditionalService")
public class InventoryAdditionalServiceController {

    private final InventoryAdditionalServiceService inventoryAdditionalServiceService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<InventoryAdditionalServiceDto> find(@Parameter(hidden = true) @Filter Specification<InventoryAdditionalService> spec,
            @Parameter(hidden = true) Pageable pageable,
            @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapSlice(inventoryAdditionalServiceService.find(spec, pageable), InventoryAdditionalServiceDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryAdditionalServiceDto create(@Valid @RequestBody CreateInventoryAdditionalService cDto) {
        return modelConverter.map(inventoryAdditionalServiceService.create(cDto), InventoryAdditionalServiceDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryAdditionalServiceDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateInventoryAdditionalService uDto) {
        return modelConverter.map(inventoryAdditionalServiceService.update(id, uDto), InventoryAdditionalServiceDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryAdditionalServiceDto get(@PathVariable("id") Long id) {
        return modelConverter.map(inventoryAdditionalServiceService.get(id), InventoryAdditionalServiceDto.class);
    }

    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcel(@Parameter(hidden = true) @Filter Specification<InventoryAdditionalService> spec,
            @Parameter(hidden = true) Pageable pageable,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExcelRequest excelRequest) {
        ByteArrayResource resource = inventoryAdditionalServiceService.exportExcel(spec, pageable, excelRequest.toExcelGeneratorBuilder());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=inventory_additional_services.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
    }
}
