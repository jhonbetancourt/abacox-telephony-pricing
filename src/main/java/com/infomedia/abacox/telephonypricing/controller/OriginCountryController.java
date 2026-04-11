package com.infomedia.abacox.telephonypricing.controller;


import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.origincountry.OriginCountryDto;
import com.infomedia.abacox.telephonypricing.dto.origincountry.CreateOriginCountry;
import com.infomedia.abacox.telephonypricing.dto.origincountry.UpdateOriginCountry;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.OriginCountry;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.security.permissions.Permissions;
import com.infomedia.abacox.telephonypricing.service.OriginCountryService;
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
@Tag(name = "OriginCountry", description = "OriginCountry API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/originCountry")
public class OriginCountryController {

    private final OriginCountryService originCountryService;
    private final ModelConverter modelConverter;

    @RequiresPermission(Permissions.NUMBERING_READ)
    @Operation(summary = "List origin countries")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<OriginCountryDto> find(@Parameter(hidden = true) @Filter Specification<OriginCountry> spec
            , @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(originCountryService.find(spec, pageable), OriginCountryDto.class);
    }

    @RequiresPermission(Permissions.NUMBERING_CREATE)
    @Operation(summary = "Create an origin country")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public OriginCountryDto create(@Valid @RequestBody CreateOriginCountry createOriginCountry) {
        return modelConverter.map(originCountryService.create(createOriginCountry), OriginCountryDto.class);
    }

    @RequiresPermission(Permissions.NUMBERING_UPDATE)
    @Operation(summary = "Update an origin country")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public OriginCountryDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateOriginCountry uDto) {
        return modelConverter.map(originCountryService.update(id, uDto), OriginCountryDto.class);
    }

    @RequiresPermission(Permissions.NUMBERING_UPDATE)
    @Operation(summary = "Change origin country activation status")
    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public OriginCountryDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(originCountryService.changeActivation(id, activationDto.getActive()), OriginCountryDto.class);
    }

    @RequiresPermission(Permissions.NUMBERING_READ)
    @Operation(summary = "Get origin country by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    private OriginCountryDto get(@PathVariable("id") Long id) {
        return modelConverter.map(originCountryService.get(id), OriginCountryDto.class);
    }

    @RequiresPermission(Permissions.NUMBERING_READ)
    @Operation(summary = "Export origin countries to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<OriginCountry> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {

        
        StreamingResponseBody body = out ->
            originCountryService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=origin_countries.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}