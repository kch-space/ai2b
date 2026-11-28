package com.ai2b.crypto.integration;

import java.math.BigDecimal;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

@Service
public class IntegrationClients {
    private final SafeHttpTransport http;
    private final IntegrationService configs;
    public IntegrationClients(SafeHttpTransport http, IntegrationService configs) {this.http=http;this.configs=configs;}
    public Map<String,Object> telegramConnect() {
        return telegramConnect(configs.ready("TELEGRAM",false));
    }
    public Map<String,Object> telegramConnect(IntegrationService.Config c) {
        JsonNode body=telegram(c,"getMe",Map.of());
        String username=body.path("result").path("username").asText();
        if(!username.matches("[A-Za-z0-9_]{5,64}"))throw new IntegrationException(502,"机器人响应缺少身份信息");
        return Map.of("success",true,"botUsername",username,"message","机器人身份验证成功；尚未发送消息");
    }
    public String telegramSend(IntegrationService.Config c,String chat,String text) {
        if(!chat.matches("-?\\d{1,20}"))throw new IntegrationException(400,"请先保存有效 Chat ID");
        JsonNode body=telegram(c,"sendMessage",Map.of("chat_id",chat,"text",text.substring(0,Math.min(3500,text.length())),"disable_web_page_preview",true));
        JsonNode id=body.path("result").path("message_id");
        if(!id.isIntegralNumber())throw new IntegrationException(502,"Telegram 未确认 message_id；发送结果未知",false,15);
        return "telegram:"+id.asText();
    }
    private JsonNode telegram(IntegrationService.Config c,String method,Map<String,Object> body) {
        JsonNode response=http.request("https://api.telegram.org/bot"+configs.secret(c)+"/"+method,"POST",null,body,true);
        if(!response.path("ok").asBoolean()) {
            int code=response.path("error_code").asInt(502);
            long retry=Math.max(1,Math.min(3600,response.path("parameters").path("retry_after").asLong(15)));
            throw new IntegrationException(code==429?429:502,"Telegram 返回错误码 "+code,code!=429 && code<500,retry);
        }
        return response;
    }
    public Map<String,Object> aiConnect() {
        return aiConnect(configs.ready("AI",false));
    }
    public Map<String,Object> aiConnect(IntegrationService.Config c) {
        String model=IntegrationService.text(c.settings(),"model");
        JsonNode body=http.request(IntegrationService.text(c.settings(),"baseUrl")+"/models","GET",configs.secret(c),null,false);
        if(!body.path("data").isArray())throw new IntegrationException(502,"模型列表响应不符合 OpenAI-compatible 格式");
        boolean found=false;
        for(JsonNode entry:body.path("data"))if(model.equals(entry.path("id").asText()))found=true;
        return Map.of("success",true,"modelAvailable",found,"message",found?"连接成功，模型列表包含配置模型；未生成回复":"连接成功，但模型列表未包含配置模型；仍需回复测试");
    }
    public String aiReply(IntegrationService.Config c,String system,String user) {
        String model=IntegrationService.text(c.settings(),"model");
        if(model.isEmpty())throw new IntegrationException(400,"请先保存模型 ID");
        Map<String,Object> body=Map.of("model",model,"temperature",new BigDecimal(IntegrationService.text(c.settings(),"temperature")),
                "max_tokens",Integer.parseInt(IntegrationService.text(c.settings(),"maxTokens")),
                "messages",List.of(Map.of("role","system","content",system),Map.of("role","user","content",user)));
        JsonNode result=http.request(IntegrationService.text(c.settings(),"baseUrl")+"/chat/completions","POST",configs.secret(c),body,false);
        String reply=result.path("choices").path(0).path("message").path("content").asText();
        if(reply.isBlank() || reply.length()>16000)throw new IntegrationException(502,"模型未返回有效文本回复，或回复超过长度限制");
        return reply.replace(configs.secret(c), "[REDACTED]");
    }
}
