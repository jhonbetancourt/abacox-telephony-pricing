package com.infomedia.abacox.telephonypricing.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@Tag(name = "Health", description = "Health controller")
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public ResponseEntity healthCheck() {
        return ResponseEntity.ok(null);
    }
}
