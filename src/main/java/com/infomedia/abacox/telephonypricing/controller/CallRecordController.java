package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.callrecord.CallRecordDto;
import com.infomedia.abacox.telephonypricing.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.service.CallRecordService;
import com.infomedia.abacox.telephonypricing.service.ConfigurationService;
import com.turkraft.springfilter.boot.Filter;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@Tag(name = "Call Record", description = "Operations related to call records")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/callRecord")
public class CallRecordController {

    private final CallRecordService callRecordService;
    private final ModelConverter modelConverter;


    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<CallRecordDto> find(@Parameter(hidden = true) @Filter Specification<CallRecord> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapPage(callRecordService.find(spec, pageable), CallRecordDto.class);
    }
}
