package com.ai2b.crypto.integration;

import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SafeHttpTransport {
    private final Set<String> allowedHosts;
    private final boolean allowLocal;
    private final ObjectMapper mapper;
    public SafeHttpTransport(ObjectMapper mapper, @Value("${integrations.ai-allowed-hosts:api.openai.com}") String hosts,
            @Value("${integrations.allow-local-ai:false}") boolean allowLocal) {
        this.mapper = mapper; this.allowLocal = allowLocal;
        allowedHosts = new HashSet<>();
        for (String host : hosts.split(",")) if (!host.isBlank()) allowedHosts.add(host.trim().toLowerCase(Locale.ROOT));
    }
    public Set<String> allowedHosts() { return Set.copyOf(allowedHosts); }
    public boolean allowLocal() { return allowLocal; }
    public URI validateAI(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || value.length() > 500 || value.contains("\\")) throw new IllegalArgumentException();
            boolean local = localHost(host);
            if (local) {
                if (!allowLocal || !Set.of("http", "https").contains(uri.getScheme())) throw new IllegalArgumentException();
            } else if (!uri.getScheme().equals("https") || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException e) { throw new IntegrationException(400, "模型地址不在服务端允许范围：远程需 HTTPS、443 及主机白名单，本地模型需显式开启"); }
    }
    private static boolean localHost(String host) { return Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host.toLowerCase(Locale.ROOT)); }
    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] a = address.getAddress();
        if (a.length == 16) return (a[0] & 0xe0) == 0x20; // Global unicast only; excludes ULA, mapped IPv4, local/multicast.
        int x = a[0] & 255, y = a[1] & 255;
        return x != 0 && x != 10 && x != 127 && x < 224 && !(x == 100 && y >= 64 && y <= 127)
                && !(x == 169 && y == 254) && !(x == 172 && y >= 16 && y <= 31) && !(x == 192 && (y == 168 || y == 0 || y == 2))
                && !(x == 198 && (y == 18 || y == 19 || y == 51)) && !(x == 203 && y == 0);
    }
    /** DNS checked at connection time and pinned by OkHttp; never follows redirects or uses system proxy. */
    public JsonNode request(String url, String method, String bearer, Object body, boolean telegram) {
        URI uri = telegram ? URI.create(url) : validateAI(url);
        if (telegram && (!"api.telegram.org".equals(uri.getHost()) || !"https".equals(uri.getScheme()) || uri.getPort() != -1)) {
            throw new IntegrationException(400, "Telegram 地址无效");
        }
        boolean local = !telegram && localHost(uri.getHost()) && allowLocal;
        OkHttpClient client = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS).dns(host -> {
                    List<InetAddress> addresses = Arrays.asList(InetAddress.getAllByName(host));
                    if (addresses.isEmpty() || addresses.stream().anyMatch(a -> local ? !a.isLoopbackAddress() : !publicAddress(a))) throw new UnknownHostException("Destination rejected");
                    return addresses;
                }).build();
        try {
            Request.Builder builder = new Request.Builder().url(url).header("Accept", "application/json");
            if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
            RequestBody payload = body == null ? null : RequestBody.create(mapper.writeValueAsString(body), MediaType.get("application/json; charset=utf-8"));
            try (Response response = client.newCall(builder.method(method, payload).build()).execute()) {
                int status = response.code();
                if (!response.isSuccessful()) {
                    if (telegram && uri.getPath().endsWith("/getUpdates") && status == 409)
                        throw new IntegrationException(409, "Telegram 更新读取冲突：请检查已有 webhook 或其他 getUpdates 程序；不会自动删除 webhook，可手动填写 Chat ID。");
                    long retry = 0;
                    try { retry = Math.min(3600, Math.max(1, Long.parseLong(response.header("Retry-After", "5")))); } catch (NumberFormatException ignored) { retry = 5; }
                    throw new IntegrationException(status == 429 ? 429 : 502, "外部服务返回 HTTP " + status,
                            status != 429 && status < 500, retry);
                }
                if (response.body() == null) throw new IntegrationException(502, "外部服务响应为空");
                byte[] bytes = response.body().byteStream().readNBytes(131073);
                if (bytes.length > 131072) throw new IntegrationException(502, "外部服务响应超过安全大小限制");
                JsonNode result = mapper.readTree(bytes);
                if (result == null || !result.isObject()) throw new IntegrationException(502, "外部响应不是有效 JSON 对象");
                return result;
            }
        } catch (IntegrationException e) { throw e; }
        catch (Exception e) { throw new IntegrationException(502, "外部服务网络、DNS 或响应异常；请求结果未知", false, 15); }
        finally { client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown(); }
    }
}
