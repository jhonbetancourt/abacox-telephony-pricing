package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.bandgroup.BandGroupDto;
import com.infomedia.abacox.telephonypricing.dto.bandgroup.CreateBandGroup;
import com.infomedia.abacox.telephonypricing.dto.bandgroup.UpdateBandGroup;
import com.infomedia.abacox.telephonypricing.dto.jobposition.JobPositionLegacyLoad;
import com.infomedia.abacox.telephonypricing.entity.BandGroup;
import com.infomedia.abacox.telephonypricing.service.BandGroupService;
import com.infomedia.abacox.telephonypricing.service.LegacyDataLoadingService;
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

import java.io.ByteArrayInputStream;
import java.util.Base64;

@RequiredArgsConstructor
@RestController
@Tag(name = "Legacy Data Loading", description = "Legacy Data Loading Controller")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/legacyDataLoading")
public class LegacyDataLoadingController {

    private final LegacyDataLoadingService legacyDataLoadingService;
    private final ModelConverter modelConverter;

    @PostMapping(value = "csv/jobPosition", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public void csvJobPosition(@Valid @RequestBody JobPositionLegacyLoad legacyLoadDto) {
        ByteArrayInputStream csvInputStream = new ByteArrayInputStream(Base64.getDecoder().decode(legacyLoadDto.getCsvFileBase64()));
        legacyDataLoadingService.loadJobPositionData(csvInputStream, legacyLoadDto.getLegacyMapping());
    }
}