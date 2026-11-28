package com.ai2b.crypto.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import com.ai2b.crypto.alert.AlertEngine;
import com.ai2b.crypto.instrument.Instrument;
import com.ai2b.crypto.kline.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties="integrations.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class IntegrationTest {
    @Autowired IntegrationService configs;
    @Autowired IntegrationClients clients;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired KlineRepository klines;
    @Autowired TransactionTemplate tx;
    @MockitoBean SafeHttpTransport http;
    private final String token="123456789:abcdefghijklmnopqrstuvw";
    private Map<String,Object> tg(String chat) {return Map.of("chatId",chat,"minimumSeverity","P1","includeEvidence",true);}
    private Map<String,Object> ai() {return Map.of("baseUrl","https://api.openai.com/v1","model","test-model","temperature","0.2","maxTokens","1024");}
    @BeforeEach void defaults(){when(http.allowedHosts()).thenReturn(Set.of("api.openai.com"));}
    @Test void encryptedSecretsAreNeverReturnedAndBlankPreservesExplicitDeleteClears() throws Exception {
        var saved=configs.save("TELEGRAM",new IntegrationService.Update(0,false,tg("-1001"),token,false));
        assertThat(saved.toString()).doesNotContain(token);
        assertThat(jdbc.queryForObject("SELECT secret_ciphertext FROM integration_config WHERE kind='TELEGRAM'",String.class)).doesNotContain(token);
        assertThat(configs.secret(configs.ready("TELEGRAM",false))).isEqualTo(token);
        configs.save("TELEGRAM",new IntegrationService.Update(1,false,tg("-1001"),"",false));
        assertThat(configs.secret(configs.ready("TELEGRAM",false))).isEqualTo(token);
        assertThatThrownBy(()->configs.save("TELEGRAM",new IntegrationService.Update(0,false,tg("-1001"),null,false))).isInstanceOf(IntegrationException.class);
        configs.save("TELEGRAM",new IntegrationService.Update(2,false,tg("-1001"),null,true));
        assertThat(configs.config("TELEGRAM").ciphertext()).isNull();
        mvc.perform(get("/api/v1/integrations")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(token))));
        assertThat(configs.audits().toString()).doesNotContain(token);
    }
    @Test void tokenCanBeSavedBeforeChatIdButCannotEnableNotificationsYet() {
        var saved=configs.save("TELEGRAM",new IntegrationService.Update(0,false,tg(""),token,false));
        assertThat(saved).containsEntry("enabled",false).containsEntry("secretConfigured",true);
        assertThat(configs.ready("TELEGRAM",false).settings()).containsEntry("chatId","");
        assertThatThrownBy(()->configs.save("TELEGRAM",new IntegrationService.Update(1,true,tg(""),null,false)))
                .isInstanceOf(IntegrationException.class).hasMessageContaining("启用前");
        verify(http,never()).request(anyString(),anyString(),any(),any(),anyBoolean());
    }
    @Test void enableRequiresSecretAndFieldsAndServerRejectsInvalidValues(){
        assertThatThrownBy(()->configs.save("TELEGRAM",new IntegrationService.Update(0,true,tg(""),null,false))).isInstanceOf(IntegrationException.class);
        assertThatThrownBy(()->configs.save("TELEGRAM",new IntegrationService.Update(0,true,tg("phone"),token,false))).isInstanceOf(IntegrationException.class);
        assertThatThrownBy(()->configs.save("TELEGRAM",new IntegrationService.Update(0,false,tg("-1001"),"bad",false))).isInstanceOf(IntegrationException.class);
        var values=new HashMap<>(ai());values.put("maxTokens","0");
        assertThatThrownBy(()->configs.save("AI",new IntegrationService.Update(0,false,values,"key",false))).isInstanceOf(IntegrationException.class);
    }
    @Test void localGuardRejectsRemoteOriginAndMissingExplicitHeaders() throws Exception {
        String body=mapper.writeValueAsString(new IntegrationService.Update(0,false,tg("-1001"),token,false));
        mvc.perform(put("/api/v1/integrations/telegram").contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/integrations/telegram").header("X-AI2B-Action","integration-admin").header("Origin","https://evil.example").contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/integrations").with(r->{r.setRemoteAddr("10.0.0.5");return r;})).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/integrations").with(r->{r.setServerName("evil.example");return r;})).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/integrations/telegram").header("X-AI2B-Action","integration-admin").header("Origin","http://127.0.0.1:5173").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.secretConfigured").value(true));
        verify(http,never()).request(anyString(),anyString(),any(),any(),anyBoolean());
    }
    @Test void realClientsSendExpectedRequestAndRedactEchoedKey() throws Exception {
        configs.save("TELEGRAM",new IntegrationService.Update(0,false,tg("-1001"),token,false));
        when(http.request(contains("getMe"),eq("POST"),isNull(),any(),eq(true))).thenReturn(mapper.readTree("{\"ok\":true,\"result\":{\"username\":\"test_bot\"}}"));
        assertThat(clients.telegramConnect()).containsEntry("botUsername","test_bot");
        when(http.request(contains("sendMessage"),eq("POST"),isNull(),any(),eq(true))).thenReturn(mapper.readTree("{\"ok\":true,\"result\":{\"message_id\":42}}"));
        assertThat(clients.telegramSend(configs.ready("TELEGRAM",false),"-1001","test")).isEqualTo("telegram:42");
        configs.save("AI",new IntegrationService.Update(0,false,ai(),"test-secret",false));
        when(http.request(endsWith("/models"),eq("GET"),eq("test-secret"),isNull(),eq(false))).thenReturn(mapper.readTree("{\"data\":[{\"id\":\"test-model\"}]}"));
        assertThat(clients.aiConnect()).containsEntry("modelAvailable",true);
        when(http.request(endsWith("/chat/completions"),eq("POST"),eq("test-secret"),any(),eq(false)))
                .thenReturn(mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"echo test-secret\"}}]}"));
        assertThat(clients.aiReply(configs.ready("AI",false),"system","test")).isEqualTo("echo [REDACTED]");
        verify(http).request(eq("https://api.openai.com/v1/chat/completions"),eq("POST"),eq("test-secret"),argThat(body->body instanceof Map<?,?> m && m.get("model").equals("test-model")),eq(false));
    }
    @Test void testsRequireConfirmationBeforeNetwork() throws Exception {
        mvc.perform(post("/api/v1/integrations/ai/test/reply").header("X-AI2B-Action","integration-admin").contentType("application/json")
                .content("{\"confirmed\":false,\"version\":0}")).andExpect(status().isBadRequest());
        verify(http,never()).request(anyString(),anyString(),any(),any(),anyBoolean());
    }
    private String event(boolean telegram) {
        if(telegram)configs.save("TELEGRAM",new IntegrationService.Update(configs.config("TELEGRAM").version(),true,tg("-1001"),token,false));
        Instant start=Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        jdbc.update("""
                INSERT INTO instrument(exchange,market_type,symbol,base_asset,quote_asset,margin_asset,contract_type,status,price_tick_size,quantity_step_size,created_at,updated_at)
                VALUES ('BINANCE','USD_M_FUTURES','BTCUSDT','BTC','USDT','USDT','PERPETUAL','TRADING',0.01,0.001,?,?)
                """,Timestamp.from(start),Timestamp.from(start));
        Instrument instrument=klines.findInstrument("BTCUSDT").orElseThrow();
        var bars=new ArrayList<Kline>();
        for(int i=0;i<6;i++){Instant open=start.plusSeconds(i*60L);BigDecimal p=new BigDecimal(i==5?"89":"100");bars.add(new Kline(open,open.plusMillis(59999),p,p,p,p,BigDecimal.ONE,BigDecimal.ONE,1,BigDecimal.ZERO,BigDecimal.ZERO,open.plusSeconds(62)));}
        klines.saveLivePage(instrument,bars,start,start,start.plusSeconds(300),start.plusSeconds(362));
        var engine=new AlertEngine(jdbc,klines,mapper,tx,Clock.fixed(start.minusSeconds(1),ZoneOffset.UTC),false);engine.integrations(configs);
        engine.evaluate("BTCUSDT",start.plusSeconds(365));engine.evaluate("BTCUSDT",start.plusSeconds(365));
        return jdbc.queryForObject("SELECT id FROM alert_event",String.class);
    }
    @Test void enabledTelegramAddsOnlyOneRealOutboxAndReconfigureCancelsIt(){
        event(true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE channel='MOCK'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE channel='TELEGRAM'",Long.class)).isEqualTo(1);
        configs.save("TELEGRAM",new IntegrationService.Update(1,false,tg("-1002"),null,false));
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE channel='TELEGRAM'",String.class)).isEqualTo("CANCELLED");
    }
    @Test void telegramWorkerDeliversRealStatusAndDefiniteRateLimitCanRetry() throws Exception {
        event(true);Instant now=Instant.now().plusSeconds(2000);
        var worker=new TelegramWorker(jdbc,tx,configs,clients,Clock.fixed(now,ZoneOffset.UTC),true);
        var job=worker.claim(now);assertThat(job).isNotNull();
        worker.finish(job,null,new IntegrationException(429,"rate limited",false,30),now);
        assertThat(worker.claim(now.plusSeconds(29))).isNull();
        var retry=worker.claim(now.plusSeconds(31));worker.finish(retry,"telegram:42",null,now.plusSeconds(32));
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE channel='TELEGRAM'",String.class)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT provider_message_id FROM notification_outbox WHERE channel='TELEGRAM'",String.class)).isEqualTo("telegram:42");
    }
    @Test void expiredRealLeaseAndAmbiguousNetworkOutcomeNeverBlindlyResend(){
        event(true);Instant now=Instant.now().plusSeconds(2000);
        var worker=new TelegramWorker(jdbc,tx,configs,clients,Clock.fixed(now,ZoneOffset.UTC),true);
        var job=worker.claim(now);assertThat(job).isNotNull();
        assertThat(worker.claim(now.plusSeconds(31))).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE channel='TELEGRAM'",String.class)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT last_error FROM notification_outbox WHERE channel='TELEGRAM'",String.class)).contains("delivery unknown");
        verify(http,never()).request(anyString(),anyString(),any(),any(),anyBoolean());
    }
    @Test void explicitEventAnalysisPersistsEvidenceAndReportWithoutBlockingAlerts() throws Exception {
        String id=event(false);
        configs.save("AI",new IntegrationService.Update(0,true,ai(),"test-key",false));
        when(http.request(endsWith("/chat/completions"),eq("POST"),eq("test-key"),any(),eq(false)))
                .thenReturn(mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"测试研究结果，需核实。\"}}]}"));
        mvc.perform(post("/api/v1/integrations/ai/test/analyze").header("X-AI2B-Action","integration-admin").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("confirmed",true,"version",1,"eventId",id))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value("测试研究结果，需核实。"));
        mvc.perform(get("/api/v1/integrations/ai/reports/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].model").value("test-model"));
        assertThat(jdbc.queryForObject("SELECT evidence_json FROM integration_ai_report",String.class)).contains("changeRatio");
    }
    @Test void workerUsesTelegramAdapterAndMockQueueIsNotClaimed() throws Exception {
        event(true);
        when(http.request(contains("sendMessage"),eq("POST"),isNull(),any(),eq(true)))
                .thenReturn(mapper.readTree("{\"ok\":true,\"result\":{\"message_id\":99}}"));
        var worker=new TelegramWorker(jdbc,tx,configs,clients,Clock.fixed(Instant.now().plusSeconds(2000),ZoneOffset.UTC),true);
        assertThat(worker.runOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE channel='TELEGRAM'",String.class)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE channel='MOCK'",String.class)).isEqualTo("PENDING");
        assertThat(worker.runOne()).isFalse();
    }
    @Test void ambiguousSendFailureIsDeadAndErrorsContainNoCredentials() {
        event(true);Instant now=Instant.now().plusSeconds(2000);
        var worker=new TelegramWorker(jdbc,tx,configs,clients,Clock.fixed(now,ZoneOffset.UTC),true);
        var job=worker.claim(now);
        worker.finish(job,null,new IntegrationException(502,"网络结果未知",false,15),now);
        assertThat(worker.claim(now.plusSeconds(60))).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE channel='TELEGRAM'",String.class)).isEqualTo("DEAD");
        assertThat(configs.audits().toString()).doesNotContain(token);
    }
    @Test void savedVersionAndCooldownProtectRealTestCalls() throws Exception {
        configs.save("TELEGRAM",new IntegrationService.Update(0,false,tg("-1001"),token,false));
        when(http.request(contains("getMe"),eq("POST"),isNull(),any(),eq(true)))
                .thenReturn(mapper.readTree("{\"ok\":true,\"result\":{\"username\":\"test_bot\"}}"));
        // New controller gives this test an isolated local request gate.
        var controller=new IntegrationController(configs,clients,jdbc,Clock.fixed(Instant.now(),ZoneOffset.UTC));
        assertThat(controller.test("telegram","connect",new IntegrationController.Confirmation(true,null,1))).containsEntry("success",true);
        assertThatThrownBy(()->controller.test("telegram","send",new IntegrationController.Confirmation(true,null,1)))
                .isInstanceOf(IntegrationException.class).hasMessageContaining("频繁");
        var fresh=new IntegrationController(configs,clients,jdbc,Clock.fixed(Instant.now(),ZoneOffset.UTC));
        assertThatThrownBy(()->fresh.test("telegram","connect",new IntegrationController.Confirmation(true,null,0)))
                .isInstanceOf(IntegrationException.class).hasMessageContaining("已变更");
        verify(http,times(1)).request(anyString(),anyString(),any(),any(),anyBoolean());
    }

}
