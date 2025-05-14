package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.cdr.CdrProcessingRequest;
import com.infomedia.abacox.telephonypricing.cdr.CdrProcessingService;
import com.infomedia.abacox.telephonypricing.dto.generic.MessageResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RequiredArgsConstructor
@RestController
@Tag(name = "CdrTestController", description = "CDR Test Controller")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@Log4j2
@RequestMapping("/api/cdr")
public class CdrTestController {

    private final CdrProcessingService cdrProcessingService;

    @PostMapping(value = "/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public MessageResponse processCdr(@RequestParam("file") MultipartFile file,
                                       @RequestParam("communicationLocationId") Long communicationLocationId) {
        log.info("Processing CDR file: {}", file.getOriginalFilename());
        try {
            cdrProcessingService.processCdrStream(file.getInputStream(),
                    CdrProcessingRequest.builder()
                            .communicationLocationId(communicationLocationId)
                            .sourceDescription(file.getOriginalFilename())
                            .build());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new MessageResponse("CDR processing started successfully.");
    }
}