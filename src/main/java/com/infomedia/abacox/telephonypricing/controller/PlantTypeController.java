package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.planttype.PlantTypeDto;
import com.infomedia.abacox.telephonypricing.dto.planttype.CreatePlantType;
import com.infomedia.abacox.telephonypricing.dto.planttype.UpdatePlantType;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.entity.PlantType;
import com.infomedia.abacox.telephonypricing.service.PlantTypeService;
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
@Tag(name = "PlantType", description = "PlantType API")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/plantType")
public class PlantTypeController {

    private final PlantTypeService plantTypeService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<PlantTypeDto> find(@Parameter(hidden = true) @Filter Specification<PlantType> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapPage(plantTypeService.find(spec, pageable), PlantTypeDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public PlantTypeDto create(@Valid @RequestBody CreatePlantType createPlantType) {
        return modelConverter.map(plantTypeService.create(createPlantType), PlantTypeDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public PlantTypeDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdatePlantType uDto) {
        return modelConverter.map(plantTypeService.update(id, uDto), PlantTypeDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    private PlantTypeDto get(@PathVariable("id") Long id) {
        return modelConverter.map(plantTypeService.get(id), PlantTypeDto.class);
    }

    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public PlantTypeDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(plantTypeService.changeActivation(id, activationDto.getActive()), PlantTypeDto.class);
    }
}