package com.ai2b.crypto.integration;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IntegrationService {
    public record Config(String kind, long version, boolean enabled, Map<String,Object> settings, String ciphertext) { }
    public record Update(long version, boolean enabled, Map<String,Object> settings, String secret, boolean clearSecret) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SecretVault vault;
    private final SafeHttpTransport http;
    private final TransactionTemplate tx;
    private final Clock clock;
    public IntegrationService(JdbcTemplate jdbc, ObjectMapper mapper, SecretVault vault, SafeHttpTransport http, TransactionTemplate tx, Clock clock) {
        this.jdbc=jdbc; this.mapper=mapper; this.vault=vault; this.http=http; this.tx=tx; this.clock=clock;
    }
    public Config config(String kind) {
        requireKind(kind);
        return jdbc.query("SELECT * FROM integration_config WHERE kind=?", (rs,n) -> new Config(kind,rs.getLong("version_number"),rs.getBoolean("enabled"),
                parse(rs.getString("settings_json")),rs.getString("secret_ciphertext")),kind).getFirst();
    }
    public Map<String,Object> view(String kind) {
        Config c=config(kind);
        return Map.of("kind",kind,"version",c.version(),"enabled",c.enabled(),"settings",c.settings(),"secretConfigured",c.ciphertext()!=null);
    }
    public Map<String,Object> overview() {
        return Map.of("telegram",view("TELEGRAM"),"ai",view("AI"),"aiAllowedHosts",http.allowedHosts(),"allowLocalAI",http.allowLocal(),"audit",audits());
    }
    public Map<String,Object> save(String kind, Update update) {
        requireKind(kind);
        if (update.settings()==null || update.settings().size()>10 || update.secret()!=null && update.secret().length()>500) throw new IntegrationException(400,"配置参数无效");
        Map<String,Object> clean=validate(kind,update.settings());
        return tx.execute(ignored -> {
            jdbc.queryForObject("SELECT version_number FROM integration_config WHERE kind=? FOR UPDATE",Long.class,kind);
            Config old=config(kind);
            if (old.version()!=update.version()) throw new IntegrationException(409,"配置已变更，请刷新后重试");
            String cipher=old.ciphertext();
            if (update.clearSecret()) cipher=null;
            if (update.secret()!=null && !update.secret().isBlank()) {
                String value=update.secret().trim();
                if (kind.equals("TELEGRAM") ? !value.matches("\\d{5,20}:[A-Za-z0-9_-]{20,100}") : value.chars().anyMatch(Character::isWhitespace)) throw new IntegrationException(400,"密钥格式无效");
                cipher=vault.encrypt(kind,value);
            }
            if (update.enabled()) {
                if (cipher==null || (kind.equals("TELEGRAM") && text(clean,"chatId").isEmpty()) || (kind.equals("AI") && text(clean,"model").isEmpty())) {
                    throw new IntegrationException(400,"启用前需配置密钥和接收人/模型");
                }
                vault.decrypt(kind,cipher); // Fail closed on missing/replaced encryption key.
            }
            jdbc.update("UPDATE integration_config SET version_number=version_number+1,enabled=?,settings_json=?,secret_ciphertext=?,updated_at=? WHERE kind=?",
                    update.enabled(),json(clean),cipher,Timestamp.from(clock.instant()),kind);
            // Reconfiguration invalidates pending old-target tasks, rather than redirecting them.
            if (kind.equals("TELEGRAM")) jdbc.update("""
                    UPDATE notification_outbox SET status='CANCELLED',lease_token=NULL,lease_until=NULL,last_error='Configuration changed; old task cancelled'
                    WHERE channel='TELEGRAM' AND status IN ('PENDING','PROCESSING')
                    """);
            audit(kind,"SAVE","SUCCESS",update.enabled()?"Enabled by local user":"Saved disabled configuration");
            return view(kind);
        });
    }
    private Map<String,Object> validate(String kind,Map<String,Object> values) {
        if (kind.equals("TELEGRAM")) {
            String chat=text(values,"chatId"); String level=text(values,"minimumSeverity");
            if (!chat.isEmpty() && !chat.matches("-?\\d{1,20}") || !Set.of("P0","P1").contains(level) || !(values.get("includeEvidence") instanceof Boolean)) throw new IntegrationException(400,"Telegram 接收人或等级参数无效");
            return Map.of("chatId",chat,"minimumSeverity",level,"includeEvidence",values.get("includeEvidence"));
        }
        String base=text(values,"baseUrl"),model=text(values,"model"),temperature=text(values,"temperature"),max=text(values,"maxTokens");
        http.validateAI(base);
        try {
            if(temperature.length()>16 || max.length()>5 || model.length()>128 || model.contains("\n") || model.contains("\r")) throw new IllegalArgumentException();
            BigDecimal t=new BigDecimal(temperature);
            if(t.signum()<0 || t.compareTo(new BigDecimal("2"))>0 || !max.matches("\\d{1,5}") || Integer.parseInt(max)<1 || Integer.parseInt(max)>32768) throw new IllegalArgumentException();
        } catch(RuntimeException e) {throw new IntegrationException(400,"模型参数无效，temperature 0–2，maxTokens 1–32768");}
        return Map.of("baseUrl",base.replaceAll("/+$",""),"model",model,"temperature",temperature,"maxTokens",max);
    }
    public Config ready(String kind, boolean requireEnabled) {
        Config c=config(kind);
        if (requireEnabled && !c.enabled()) throw new IntegrationException(409,"该集成已停用");
        if(c.ciphertext()==null) throw new IntegrationException(409,"请先保存服务端密钥");
        return c;
    }
    public String secret(Config c) {return vault.decrypt(c.kind(),c.ciphertext());}
    public void audit(String kind,String action,String outcome,String detail) {
        jdbc.update("INSERT INTO integration_audit(id,kind,action,outcome,detail,created_at) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(),kind,action,outcome,detail.substring(0,Math.min(256,detail.length())),Timestamp.from(clock.instant()));
    }
    public List<Map<String,Object>> audits() {
        return jdbc.query("SELECT id,kind,action,outcome,detail,created_at FROM integration_audit ORDER BY created_at DESC,id DESC LIMIT 30",(rs,n) ->
                Map.of("id",rs.getString(1),"kind",rs.getString(2),"action",rs.getString(3),"outcome",rs.getString(4),"detail",rs.getString(5),"createdAt",rs.getTimestamp(6).toInstant().toString()));
    }
    public String json(Object value) {try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot serialize integration data");}}
    public Map<String,Object> parse(String value) {try{return mapper.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("Invalid stored integration data");}}
    public static String text(Map<String,Object> values,String key) {Object v=values.get(key);return v instanceof String s?s.trim():"";}
    private void requireKind(String kind) {if(!Set.of("TELEGRAM","AI").contains(kind))throw new IntegrationException(404,"集成不存在");}
}
