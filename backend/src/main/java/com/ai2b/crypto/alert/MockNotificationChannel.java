package com.ai2b.crypto.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Never opens a socket and never claims real delivery. Stable id for idempotent replays. */
@Component
public class MockNotificationChannel {
    private final ObjectMapper mapper;
    public MockNotificationChannel(ObjectMapper mapper) { this.mapper = mapper; }
    public String simulate(String id, String payload) throws Exception {
        if (!mapper.readTree(payload).path("simulation").asBoolean(false)) {
            throw new IllegalArgumentException("Mock payload must explicitly declare simulation");
        }
        return "mock:" + id;
    }
}
