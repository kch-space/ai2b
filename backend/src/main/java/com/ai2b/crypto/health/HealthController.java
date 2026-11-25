package com.ai2b.crypto.health;

import java.time.Instant;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public HealthResponse health() {
        // Application availability only; no market collectors are connected yet.
        return new HealthResponse("UP", "crypto-intelligence-backend", Instant.now(),
                UUID.randomUUID().toString());
    }
}
