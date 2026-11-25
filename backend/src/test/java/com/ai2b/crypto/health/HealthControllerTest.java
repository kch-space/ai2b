package com.ai2b.crypto.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void healthReturnsApplicationStatusAndTraceableMetadata() throws Exception {
        Instant before = Instant.now();
        String body = mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("crypto-intelligence-backend"))
                .andExpect(jsonPath("$.requestId").value(matchesPattern("[0-9a-f-]{36}")))
                .andReturn().getResponse().getContentAsString();
        JsonNode response = objectMapper.readTree(body);
        Instant timestamp = Instant.parse(response.get("timestamp").asText());
        assertThat(timestamp).isBetween(before, Instant.now());
        assertThat(UUID.fromString(response.get("requestId").asText())).isNotNull();
        assertThat(response.has("marketData")).isFalse();
    }

    @Test
    void requestsHaveDistinctIds() throws Exception {
        String first = mockMvc.perform(get("/api/v1/health")).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get("/api/v1/health")).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(first).get("requestId").asText())
                .isNotEqualTo(objectMapper.readTree(second).get("requestId").asText());
    }

    @Test
    void actuatorHealthIsAvailableWithoutInternalDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void unrelatedEndpointsAreNotExposed() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/market/overview")).andExpect(status().isNotFound());
    }
}
