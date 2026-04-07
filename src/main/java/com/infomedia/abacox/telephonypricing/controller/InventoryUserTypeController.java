package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.entity.InventoryUserType;
import com.infomedia.abacox.telephonypricing.dto.inventoryusertype.CreateInventoryUserType;
import com.infomedia.abacox.telephonypricing.dto.inventoryusertype.InventoryUserTypeDto;
import com.infomedia.abacox.telephonypricing.dto.inventoryusertype.UpdateInventoryUserType;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;

import com.infomedia.abacox.telephonypricing.service.InventoryUserTypeService;
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
@Tag(name = "InventoryUserType", description = "InventoryUserType API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/inventoryUserType")
public class InventoryUserTypeController {

    private final InventoryUserTypeService inventoryUserTypeService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<InventoryUserTypeDto> find(@Parameter(hidden = true) @Filter Specification<InventoryUserType> spec,
            @Parameter(hidden = true) Pageable pageable,
            @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapSlice(inventoryUserTypeService.find(spec, pageable), InventoryUserTypeDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryUserTypeDto create(@Valid @RequestBody CreateInventoryUserType cDto) {
        return modelConverter.map(inventoryUserTypeService.create(cDto), InventoryUserTypeDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryUserTypeDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateInventoryUserType uDto) {
        return modelConverter.map(inventoryUserTypeService.update(id, uDto), InventoryUserTypeDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public InventoryUserTypeDto get(@PathVariable("id") Long id) {
        return modelConverter.map(inventoryUserTypeService.get(id), InventoryUserTypeDto.class);
    }

    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<InventoryUserType> spec,
            @Parameter(hidden = true) Pageable pageable,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExcelRequest excelRequest) {
        StreamingResponseBody body = out ->
            inventoryUserTypeService.exportExcelStreaming(spec, pageable, out, excelRequest.toExcelGeneratorBuilder());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=inventory_user_types.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}
