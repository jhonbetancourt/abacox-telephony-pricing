package com.infomedia.abacox.telephonypricing.controller;

import com.infomedia.abacox.telephonypricing.service.ConfigurationService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@Tag(name = "Configuration", description = "Configuration controller")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/configuration")
public class ConfigController {

    private final ConfigurationService configurationService;

    @PostMapping("clearCache")
    public void clearCache() {
        configurationService.clearCachedConfig();
    }
}
