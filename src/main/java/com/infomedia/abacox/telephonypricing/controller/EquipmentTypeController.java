package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.db.entity.EquipmentType;
import com.infomedia.abacox.telephonypricing.dto.equipmenttype.CreateEquipmentType;
import com.infomedia.abacox.telephonypricing.dto.equipmenttype.EquipmentTypeDto;
import com.infomedia.abacox.telephonypricing.dto.equipmenttype.UpdateEquipmentType;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;

import com.infomedia.abacox.telephonypricing.service.EquipmentTypeService;
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
@Tag(name = "EquipmentType", description = "EquipmentType API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/equipmentType")
public class EquipmentTypeController {

    private final EquipmentTypeService equipmentTypeService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<EquipmentTypeDto> find(@Parameter(hidden = true) @Filter Specification<EquipmentType> spec,
            @Parameter(hidden = true) Pageable pageable,
            @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapSlice(equipmentTypeService.find(spec, pageable), EquipmentTypeDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public EquipmentTypeDto create(@Valid @RequestBody CreateEquipmentType cDto) {
        return modelConverter.map(equipmentTypeService.create(cDto), EquipmentTypeDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public EquipmentTypeDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateEquipmentType uDto) {
        return modelConverter.map(equipmentTypeService.update(id, uDto), EquipmentTypeDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public EquipmentTypeDto get(@PathVariable("id") Long id) {
        return modelConverter.map(equipmentTypeService.get(id), EquipmentTypeDto.class);
    }

    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcel(@Parameter(hidden = true) @Filter Specification<EquipmentType> spec,
            @Parameter(hidden = true) Pageable pageable,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExcelRequest excelRequest) {
        ByteArrayResource resource = equipmentTypeService.exportExcel(spec, pageable, excelRequest.toExcelGeneratorBuilder());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=equipment_types.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
    }
}
