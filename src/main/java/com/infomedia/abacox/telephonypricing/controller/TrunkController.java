package com.infomedia.abacox.telephonypricing.controller;


import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.trunk.TrunkDto;
import com.infomedia.abacox.telephonypricing.dto.trunk.CreateTrunk;
import com.infomedia.abacox.telephonypricing.dto.trunk.UpdateTrunk;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.Trunk;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.service.TrunkService;
import com.turkraft.springfilter.boot.Filter;
import io.swagger.v3.oas.annotations.Operation;
import com.infomedia.abacox.telephonypricing.dto.generic.ExcelRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.ExportRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.FilterRequest;
import com.infomedia.abacox.telephonypricing.dto.generic.PageableRequest;
import io.swagger.v3.oas.annotations.Parameter;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RequiredArgsConstructor
@RestController
@Tag(name = "Trunk", description = "Trunk API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/trunk")
public class TrunkController {

    private final TrunkService trunkService;
    private final ModelConverter modelConverter;

    @RequiresPermission("telephony-config:read")
    @Operation(summary = "List trunks")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<TrunkDto> find(@Parameter(hidden = true) @Filter Specification<Trunk> spec
            , @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(trunkService.find(spec, pageable), TrunkDto.class);
    }

    @RequiresPermission("telephony-config:create")
    @Operation(summary = "Create a trunk")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public TrunkDto create(@Valid @RequestBody CreateTrunk createTrunk) {
        return modelConverter.map(trunkService.create(createTrunk), TrunkDto.class);
    }

    @RequiresPermission("telephony-config:update")
    @Operation(summary = "Update a trunk")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public TrunkDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateTrunk updateTrunk) {
        return modelConverter.map(trunkService.update(id, updateTrunk), TrunkDto.class);
    }

    @RequiresPermission("telephony-config:update")
    @Operation(summary = "Change trunk activation status")
    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TrunkDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(trunkService.changeActivation(id, activationDto.getActive()), TrunkDto.class);
    }

    @RequiresPermission("telephony-config:read")
    @Operation(summary = "Get trunk by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TrunkDto get(@PathVariable("id") Long id) {
        return modelConverter.map(trunkService.get(id), TrunkDto.class);
    }

    @RequiresPermission("telephony-config:read")
    @Operation(summary = "Export trunks to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<Trunk> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {

        
        StreamingResponseBody body = out ->
            trunkService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=trunks.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

}