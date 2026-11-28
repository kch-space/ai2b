package com.ai2b.crypto.integration;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Separate real channel. Telegram has no sendMessage idempotency key: ambiguous outcomes are not blindly retried. */
@Service
public class TelegramWorker {
    private static final Logger log=LoggerFactory.getLogger(TelegramWorker.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final IntegrationService configs;
    private final IntegrationClients clients;
    private final Clock clock;
    private final boolean alertsEnabled;
    public record Job(String id,String token,String recipient,String payload,long version,int attempt) { }
    public TelegramWorker(JdbcTemplate jdbc,TransactionTemplate tx,IntegrationService configs,IntegrationClients clients,Clock clock,
            @Value("${market.alerts.enabled:true}") boolean enabled) {this.jdbc=jdbc;this.tx=tx;this.configs=configs;this.clients=clients;this.clock=clock;this.alertsEnabled=enabled;}
    @Scheduled(initialDelay=25000,fixedDelay=2000)
    public void scheduledRun() {
        if(!alertsEnabled)return;
        try {for(int n=0;n<5 && runOne();n++) { }}
        catch(RuntimeException e){log.warn("Telegram worker failed ({})",e.getClass().getSimpleName());}
    }
    public Job claim(Instant now) {
        return tx.execute(ignored -> {
            jdbc.queryForObject("SELECT version_number FROM integration_config WHERE kind='TELEGRAM' FOR UPDATE",Long.class);
            var c=configs.config("TELEGRAM");
            var rows=jdbc.queryForList("""
                    SELECT * FROM notification_outbox WHERE channel='TELEGRAM'
                    AND ((status='PENDING' AND next_attempt_at<=?) OR (status='PROCESSING' AND lease_until<=?))
                    ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE
                    """,Timestamp.from(now),Timestamp.from(now));
            if(rows.isEmpty())return null;
            var row=rows.getFirst();String id=(String)row.get("id");long version=((Number)row.get("config_version")).longValue();
            if(!c.enabled() || c.version()!=version || !IntegrationService.text(c.settings(),"chatId").equals(row.get("recipient"))) {
                jdbc.update("UPDATE notification_outbox SET status='CANCELLED',lease_token=NULL,lease_until=NULL,last_error='Disabled or changed configuration' WHERE id=?",id);return null;
            }
            int attempt=((Number)row.get("attempts")).intValue();
            if(row.get("status").equals("PROCESSING")) {
                jdbc.update("UPDATE notification_outbox SET status='DEAD',lease_token=NULL,lease_until=NULL,last_error='Expired real-send lease; delivery unknown; manual review required' WHERE id=?",id);
                audit(id,attempt,"UNKNOWN","Expired real-send lease; not automatically resent",now);return null;
            }
            String token=UUID.randomUUID().toString();
            jdbc.update("UPDATE notification_outbox SET status='PROCESSING',attempts=attempts+1,lease_token=?,lease_until=? WHERE id=?",token,Timestamp.from(now.plusSeconds(30)),id);
            return new Job(id,token,(String)row.get("recipient"),(String)row.get("payload_json"),version,attempt+1);
        });
    }
    public boolean runOne() {
        Job job=claim(clock.instant());if(job==null)return false;
        try {
            var c=configs.ready("TELEGRAM",true);
            if(c.version()!=job.version())throw new IntegrationException(409,"配置已变更，取消发送");
            Map<String,Object> data=configs.parse(job.payload());
            Map<?,?> evidence=(Map<?,?>)data.get("evidence");
            String text="ai2b 市场预警 · "+data.get("symbol")+" · "+data.get("action")+" · "+data.get("severity")
                    +"\n事件："+data.get("eventId")+"\n通知："+job.id();
            if(Boolean.TRUE.equals(c.settings().get("includeEvidence")))text+="\n5m 变化比例："+evidence.get("changeRatio")+"\n基准价："+evidence.get("baselinePrice")+" → "+evidence.get("latestPrice")+"\n数据时间（UTC）："+evidence.get("dataTime");
            text+="\n仅供研究，非交易建议。";
            finish(job,clients.telegramSend(c,job.recipient(),text),null,clock.instant());
        } catch(IntegrationException e) {finish(job,null,e,clock.instant());}
        catch(RuntimeException e){finish(job,null,new IntegrationException(502,"发送结果未知，需人工核对",true,0),clock.instant());}
        return true;
    }
    public void finish(Job job,String messageId,IntegrationException failure,Instant now) {
        tx.executeWithoutResult(ignored -> {
            var rows=jdbc.queryForList("SELECT status,lease_token FROM notification_outbox WHERE id=? FOR UPDATE",job.id());
            if(rows.isEmpty() || !"PROCESSING".equals(rows.getFirst().get("status")) || !job.token().equals(rows.getFirst().get("lease_token"))) {
                audit(job.id(),job.attempt(),messageId==null?"UNKNOWN":"SENT", "In-flight task completed after cancellation; "+(messageId==null?"outcome unknown":messageId),now);return;
            }
            if(failure==null) {
                jdbc.update("UPDATE notification_outbox SET status='SENT',provider_message_id=?,sent_at=?,lease_token=NULL,lease_until=NULL WHERE id=?",messageId,Timestamp.from(now),job.id());
                audit(job.id(),job.attempt(),"SENT",null,now);
            } else {
                // 429 is a definite rejection. Unknown network/5xx outcome cannot be safely resent automatically.
                boolean retry=failure.status==429 && job.attempt()<5;
                jdbc.update("UPDATE notification_outbox SET status=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL,last_error=? WHERE id=?",
                        retry?"PENDING":failure.status==409?"CANCELLED":"DEAD",Timestamp.from(now.plusSeconds(Math.max(failure.retrySeconds,5L << (job.attempt()-1)))),failure.getMessage(),job.id());
                audit(job.id(),job.attempt(),"FAILED",failure.getMessage(),now);
            }
        });
    }
    private void audit(String id,int attempt,String outcome,String error,Instant now) {
        jdbc.update("INSERT INTO notification_attempt(id,outbox_id,attempt_number,outcome,error_message,created_at) VALUES (?,?,?,?,?,?)",UUID.randomUUID().toString(),id,attempt,outcome,error,Timestamp.from(now));
    }
}
