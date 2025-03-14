package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.costcenter.CostCenterDto;
import com.infomedia.abacox.telephonypricing.dto.costcenter.CreateCostCenter;
import com.infomedia.abacox.telephonypricing.dto.costcenter.UpdateCostCenter;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.entity.CostCenter;
import com.infomedia.abacox.telephonypricing.service.CostCenterService;
import com.turkraft.springfilter.boot.Filter;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@Tag(name = "CostCenter", description = "CostCenter API")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/costCenter")
public class CostCenterController {

    private final CostCenterService costCenterService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<CostCenterDto> find(@Parameter(hidden = true) @Filter Specification<CostCenter> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapPage(costCenterService.find(spec, pageable), CostCenterDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CostCenterDto create(@Valid @RequestBody CreateCostCenter createCostCenter) {
        return modelConverter.map(costCenterService.create(createCostCenter), CostCenterDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CostCenterDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateCostCenter uDto) {
        return modelConverter.map(costCenterService.update(id, uDto), CostCenterDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    private CostCenterDto get(@PathVariable("id") Long id) {
        return modelConverter.map(costCenterService.get(id), CostCenterDto.class);
    }
}