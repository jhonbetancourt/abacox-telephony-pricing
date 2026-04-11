package com.infomedia.abacox.telephonypricing.controller;


import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.commlocation.CommLocationDto;
import com.infomedia.abacox.telephonypricing.dto.commlocation.CreateCommLocation;
import com.infomedia.abacox.telephonypricing.dto.commlocation.UpdateCommLocation;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.security.permissions.Permissions;
import com.infomedia.abacox.telephonypricing.service.CommLocationService;
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
@Tag(name = "CommunicationLocation", description = "CommunicationLocation API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/commLocation")
public class CommLocationController {

    private final CommLocationService commLocationService;
    private final ModelConverter modelConverter;

    @RequiresPermission(Permissions.TELEPHONY_CONFIG_READ)
    @Operation(summary = "List communication locations")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<CommLocationDto> find(@Parameter(hidden = true) @Filter Specification<CommunicationLocation> spec
            , @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(commLocationService.find(spec, pageable), CommLocationDto.class);
    }

    @RequiresPermission(Permissions.TELEPHONY_CONFIG_CREATE)
    @Operation(summary = "Create a communication location")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CommLocationDto create(@Valid @RequestBody CreateCommLocation createCommLocation) {
        return modelConverter.map(commLocationService.create(createCommLocation), CommLocationDto.class);
    }

    @RequiresPermission(Permissions.TELEPHONY_CONFIG_UPDATE)
    @Operation(summary = "Update a communication location")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CommLocationDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateCommLocation uDto) {
        return modelConverter.map(commLocationService.update(id, uDto), CommLocationDto.class);
    }

    @RequiresPermission(Permissions.TELEPHONY_CONFIG_UPDATE)
    @Operation(summary = "Change communication location activation status")
    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CommLocationDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(commLocationService.changeActivation(id, activationDto.getActive()), CommLocationDto.class);
    }

    @RequiresPermission(Permissions.TELEPHONY_CONFIG_READ)
    @Operation(summary = "Get communication location by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    private CommLocationDto get(@PathVariable("id") Long id) {
        return modelConverter.map(commLocationService.get(id), CommLocationDto.class);
    }

    @RequiresPermission(Permissions.TELEPHONY_CONFIG_READ)
    @Operation(summary = "Export communication locations to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<CommunicationLocation> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {

        
        StreamingResponseBody body = out ->
            commLocationService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=comm_locations.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}