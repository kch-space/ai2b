package com.ai2b.crypto.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TelegramDiscoveryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String token = "123456789:abcdefghijklmnopqrstuvw";
    private final IntegrationService.Config config = new IntegrationService.Config("TELEGRAM", 1, false,
            Map.of("chatId", "", "minimumSeverity", "P1", "includeEvidence", true), "encrypted");
    private IntegrationService configs;
    private SafeHttpTransport http;
    private IntegrationClients clients;
    private MockMvc mvc;

    @BeforeEach void setup() throws Exception {
        configs = mock(IntegrationService.class);
        http = mock(SafeHttpTransport.class);
        clients = new IntegrationClients(http, configs);
        when(configs.secret(config)).thenReturn(token);
        when(configs.ready("TELEGRAM", false)).thenReturn(config);
        when(configs.config("TELEGRAM")).thenReturn(config);
        when(http.request(endsWith("/getMe"), eq("POST"), isNull(), any(), eq(true)))
                .thenReturn(mapper.readTree("{\"ok\":true,\"result\":{\"username\":\"test_bot\"}}"));
        updates("[]");
        var controller = new IntegrationController(configs, clients, mock(JdbcTemplate.class),
                Clock.fixed(Instant.parse("2026-11-28T00:00:00Z"), ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(new LocalAdminGuard()).build();
    }

    private void updates(String result) throws Exception {
        when(http.request(endsWith("/getUpdates"), eq("POST"), isNull(), any(), eq(true)))
                .thenReturn(mapper.readTree("{\"ok\":true,\"result\":" + result + "}"));
    }

    @Test void discoversOnlyStartCommandsDeduplicatesAndDoesNotConsumeUpdatesOrExposeMessages() throws Exception {
        updates("""
                [
                  {"message":{"text":"/start","date":1700000000,"chat":{"id":42,"type":"private","first_name":"Alice","username":"alice_user"}}},
                  {"message":{"text":"/start@test_bot private-payload","date":1700000002,"chat":{"id":-1001,"type":"supergroup","title":"Research"}}},
                  {"message":{"text":"/start newer","date":1700000003,"chat":{"id":42,"type":"private","first_name":"Alice"}}},
                  {"message":{"text":"/start old","date":1700000001,"chat":{"id":42,"type":"private","first_name":"Old Alice"}}},
                  {"message":{"text":"/start\\tgroup","date":1700000001,"chat":{"id":-99,"type":"group","title":"Group"}}},
                  {"message":{"text":"/start@other_bot","date":1700000004,"chat":{"id":55,"type":"private"}}},
                  {"message":{"text":"/starting","date":1700000004,"chat":{"id":56,"type":"private"}}},
                  {"message":{"text":"private unrelated message","date":1700000004,"chat":{"id":57,"type":"private"}}},
                  {"edited_message":{"text":"/start","date":1700000004,"chat":{"id":58,"type":"private"}}},
                  {"channel_post":{"text":"/start","date":1700000004,"chat":{"id":-59,"type":"channel"}}}
                ]
                """);
        var result = mapper.valueToTree(clients.telegramDiscoverChats(config));
        assertThat(result.path("chats").size()).isEqualTo(3);
        assertThat(result.path("chats").get(0).path("chatId").asText()).isEqualTo("42");
        assertThat(result.path("chats").get(0).path("title").asText()).isEqualTo("Alice");
        assertThat(result.path("chats").get(1).path("chatId").asText()).isEqualTo("-1001");
        assertThat(result.toString()).doesNotContain(token, "private-payload", "private unrelated message", "old");
        verify(http).request(eq("https://api.telegram.org/bot" + token + "/getUpdates"), eq("POST"), isNull(),
                eq(Map.of("timeout", 0, "limit", 100)), eq(true));
        verify(http, times(2)).request(anyString(), anyString(), any(), any(), anyBoolean());
        verify(configs, never()).save(anyString(), any());
    }

    @Test void emptyUpdatesExplainManualFallbackWithoutClaimingBinding() {
        var result = clients.telegramDiscoverChats(config);
        assertThat((List<?>) result.get("chats")).isEmpty();
        assertThat(result.get("message").toString()).contains("/start", "100", "手动填写");
    }

    @Test void malformedResultsAreRejected() throws Exception {
        updates("{}");
        assertThatThrownBy(() -> clients.telegramDiscoverChats(config)).isInstanceOf(IntegrationException.class)
                .hasMessageContaining("响应格式无效");
    }

    @Test void malformedCandidatesAreSkippedAndNamesAreRedacted() throws Exception {
        updates("""
                [
                  {"message":{"text":"/start","date":1700000000,"chat":{"id":0,"type":"private"}}},
                  {"message":{"text":"/start","date":1700000000,"chat":{"id":-9223372036854775808,"type":"private"}}},
                  {"message":{"text":"/start","date":1700000000,"chat":{"id":"42","type":"private"}}},
                  {"message":{"text":"/start","date":999999999999999999999999,"chat":{"id":43,"type":"private"}}},
                  {"message":{"text":"/start","date":-1,"chat":{"id":44,"type":"private"}}},
                  {"message":{"text":"/start","date":1700000000,"chat":{"id":45,"type":"channel"}}},
                  {"message":{"text":"/start","date":1700000000,"chat":{"id":46,"type":"private","first_name":"%s","username":"<unsafe>"}}}
                ]
                """.formatted(token));
        var result = mapper.valueToTree(clients.telegramDiscoverChats(config));
        assertThat(result.path("chats").size()).isEqualTo(1);
        assertThat(result.path("chats").get(0).path("title").asText()).isEqualTo("[REDACTED]");
        assertThat(result.path("chats").get(0).path("username").asText()).isEmpty();
        assertThat(result.toString()).doesNotContain(token);
    }

    @Test void webhookOrPollingConflictDoesNotDeleteWebhookOrSendMessage() throws Exception {
        when(http.request(endsWith("/getUpdates"), eq("POST"), isNull(), any(), eq(true)))
                .thenReturn(mapper.readTree("{\"ok\":false,\"error_code\":409,\"description\":\"untrusted provider content\"}"));
        assertThatThrownBy(() -> clients.telegramDiscoverChats(config)).isInstanceOfSatisfying(IntegrationException.class,
                e -> { assertThat(e.status).isEqualTo(409); assertThat(e.getMessage()).contains("webhook", "手动").doesNotContain("untrusted"); });
        verify(http, times(2)).request(anyString(), anyString(), any(), any(), anyBoolean());
    }

    @Test void networkFailuresPropagateWithoutRetrying() {
        when(http.request(endsWith("/getUpdates"), eq("POST"), isNull(), any(), eq(true)))
                .thenThrow(new IntegrationException(502, "network unavailable", false, 15));
        assertThatThrownBy(() -> clients.telegramDiscoverChats(config)).hasMessage("network unavailable");
        verify(http, times(1)).request(endsWith("/getUpdates"), anyString(), any(), any(), anyBoolean());
    }

    @Test void endpointRequiresConfirmationAndLocalAdminHeader() throws Exception {
        mvc.perform(post("/api/v1/integrations/telegram/test/chats").contentType("application/json")
                .content("{\"confirmed\":true,\"version\":1}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/integrations/telegram/test/chats").header("X-AI2B-Action", "integration-admin")
                .contentType("application/json").content("{\"confirmed\":false,\"version\":1}"))
                .andExpect(status().isBadRequest());
        verify(http, never()).request(anyString(), anyString(), any(), any(), anyBoolean());
    }

    @Test void staleVersionAndMissingTokenCannotReadUpdates() throws Exception {
        mvc.perform(post("/api/v1/integrations/telegram/test/chats").header("X-AI2B-Action", "integration-admin")
                .contentType("application/json").content("{\"confirmed\":true,\"version\":0}"))
                .andExpect(status().isConflict());
        verify(http, never()).request(anyString(), anyString(), any(), any(), anyBoolean());
    }

    @Test void missingTokenAndWrongIntegrationCannotReadUpdates() throws Exception {
        when(configs.ready("TELEGRAM", false)).thenThrow(new IntegrationException(409, "请先保存服务端密钥"));
        mvc.perform(post("/api/v1/integrations/ai/test/chats").header("X-AI2B-Action", "integration-admin")
                .contentType("application/json").content("{\"confirmed\":true,\"version\":1}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/integrations/telegram/test/chats").header("X-AI2B-Action", "integration-admin")
                .contentType("application/json").content("{\"confirmed\":true,\"version\":1}"))
                .andExpect(status().isConflict());
        verify(http, never()).request(anyString(), anyString(), any(), any(), anyBoolean());
    }

    @Test void endpointWorksWhileDisabledAndAuditsWithoutSavingOrEnablingAndEnforcesCooldown() throws Exception {
        mvc.perform(post("/api/v1/integrations/telegram/test/chats").header("X-AI2B-Action", "integration-admin")
                .contentType("application/json").content("{\"confirmed\":true,\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.chats").isArray())
                .andExpect(jsonPath("$.botUsername").value("test_bot"));
        verify(configs).audit(eq("TELEGRAM"), eq("CHATS"), eq("REQUESTED"), anyString());
        verify(configs).audit(eq("TELEGRAM"), eq("CHATS"), eq("SUCCESS"), anyString());
        verify(configs, never()).save(anyString(), any());
        mvc.perform(post("/api/v1/integrations/telegram/test/chats").header("X-AI2B-Action", "integration-admin")
                .contentType("application/json").content("{\"confirmed\":true,\"version\":1}"))
                .andExpect(status().isTooManyRequests());
        verify(http, times(2)).request(anyString(), anyString(), any(), any(), anyBoolean());
    }

    @Test void discardsCandidatesWhenConfigurationChangesDuringDiscovery() throws Exception {
        when(configs.config("TELEGRAM")).thenReturn(new IntegrationService.Config("TELEGRAM", 2, false, config.settings(), "other"));
        mvc.perform(post("/api/v1/integrations/telegram/test/chats").header("X-AI2B-Action", "integration-admin")
                .contentType("application/json").content("{\"confirmed\":true,\"version\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.chats").doesNotExist());
        verify(configs).audit(eq("TELEGRAM"), eq("CHATS"), eq("FAILED"), anyString());
        verify(configs, never()).save(anyString(), any());
    }
}
