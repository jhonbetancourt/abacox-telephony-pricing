package com.infomedia.abacox.telephonypricing.controller;


import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.officedetails.OfficeDetailsDto;
import com.infomedia.abacox.telephonypricing.dto.officedetails.CreateOfficeDetails;
import com.infomedia.abacox.telephonypricing.dto.officedetails.UpdateOfficeDetails;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.OfficeDetails;
import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import com.infomedia.abacox.telephonypricing.service.OfficeDetailsService;
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
@Tag(name = "OfficeDetails", description = "Office Details API")
@SecurityRequirements(value = {
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username"),
        @SecurityRequirement(name = "Tenant_Id")
})
@RequestMapping("/api/officeDetails")
public class OfficeDetailsController {

    private final OfficeDetailsService officeDetailsService;
    private final ModelConverter modelConverter;

    @RequiresPermission("company:read")
    @Operation(summary = "List office details")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Slice<OfficeDetailsDto> find(@Parameter(hidden = true) @Filter Specification<OfficeDetails> spec
            , @Parameter(hidden = true) Pageable pageable
            , @ParameterObject FilterRequest filterRequest
            , @ParameterObject PageableRequest pageableRequest) {
        return modelConverter.mapSlice(officeDetailsService.find(spec, pageable), OfficeDetailsDto.class);
    }

    @RequiresPermission("company:create")
    @Operation(summary = "Create an office details record")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public OfficeDetailsDto create(@Valid @RequestBody CreateOfficeDetails createOfficeDetails) {
        return modelConverter.map(officeDetailsService.create(createOfficeDetails), OfficeDetailsDto.class);
    }

    @RequiresPermission("company:update")
    @Operation(summary = "Update an office details record")
    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public OfficeDetailsDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateOfficeDetails uDto) {
        return modelConverter.map(officeDetailsService.update(id, uDto), OfficeDetailsDto.class);
    }

    @RequiresPermission("company:update")
    @Operation(summary = "Change office details activation status")
    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public OfficeDetailsDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(officeDetailsService.changeActivation(id, activationDto.getActive()), OfficeDetailsDto.class);
    }

    @RequiresPermission("company:read")
    @Operation(summary = "Get office details by ID")
    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public OfficeDetailsDto get(@PathVariable("id") Long id) {
        return modelConverter.map(officeDetailsService.get(id), OfficeDetailsDto.class);
    }

    @RequiresPermission("company:read")
    @Operation(summary = "Export office details to Excel")
    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> exportExcel(@Parameter(hidden = true) @Filter Specification<OfficeDetails> spec,
            @ParameterObject FilterRequest filterRequest,
            @ParameterObject ExportRequest exportRequest,
            @ParameterObject ExcelRequest excelRequest) {

        
        StreamingResponseBody body = out ->
            officeDetailsService.exportExcelStreaming(spec, exportRequest.getSortOrder(), exportRequest.getMaxRows(), out, excelRequest.toExcelGeneratorBuilder());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=office_details.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}