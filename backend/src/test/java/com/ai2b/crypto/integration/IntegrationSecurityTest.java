package com.ai2b.crypto.integration;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntegrationSecurityTest {
    @TempDir Path temp;
    @Test void persistentVaultRoundTripsWithoutPlaintextAndRejectsLostOrSwappedKey() throws Exception {
        Path path=temp.resolve("keys/integration.key");
        var vault=new SecretVault("",path.toString());
        String ciphertext=vault.encrypt("AI","test-secret");
        assertThat(ciphertext).doesNotContain("test-secret");
        assertThat(Files.getPosixFilePermissions(path)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(new SecretVault("",path.toString()).decrypt("AI",ciphertext)).isEqualTo("test-secret");
        assertThatThrownBy(()->new SecretVault("",path.toString()).decrypt("TELEGRAM",ciphertext)).isInstanceOf(IntegrationException.class);
        Files.delete(path);
        assertThatThrownBy(()->new SecretVault("",path.toString()).decrypt("AI",ciphertext)).isInstanceOf(IntegrationException.class);
        assertThat(Files.exists(path)).isFalse();
    }
    @Test void vaultRejectsWorldReadableKeysAndBadEnvironmentKeys() throws Exception {
        Path path=temp.resolve("key");Files.writeString(path,"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(()->new SecretVault("",path.toString()).encrypt("AI","secret")).isInstanceOf(IntegrationException.class);
        assertThatThrownBy(()->new SecretVault("invalid",path.toString()).encrypt("AI","secret")).isInstanceOf(IntegrationException.class);
    }
    @Test void modelTargetsAreAllowlistedAndPrivateDNSAddressesDenied() throws Exception {
        var http=new SafeHttpTransport(new ObjectMapper(),"api.openai.com",false);
        for(String url:new String[]{"https://evil.example/v1","http://api.openai.com/v1","https://api.openai.com:8443/v1","https://user:secret@api.openai.com/v1","https://api.openai.com/v1?key=secret","http://127.0.0.1:11434/v1","https://169.254.169.254/"}) {
            assertThatThrownBy(()->http.validateAI(url)).isInstanceOf(IntegrationException.class);
        }
        assertThat(http.validateAI("https://api.openai.com/v1").getHost()).isEqualTo("api.openai.com");
        for(String address:new String[]{"127.0.0.1","10.0.0.1","172.16.0.1","192.168.0.1","169.254.169.254","100.64.0.1","::1","fc00::1"})assertThat(SafeHttpTransport.publicAddress(InetAddress.getByName(address))).isFalse();
        assertThat(SafeHttpTransport.publicAddress(InetAddress.getByName("8.8.8.8"))).isTrue();
    }
    @Test void localModelIsOptInAndRedirectsAreNeverFollowed() throws Exception {
        var http=new SafeHttpTransport(new ObjectMapper(),"",true);
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicInteger targetHits=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/v1/models",e->{e.getResponseHeaders().set("Location","/private");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/private",e->{targetHits.incrementAndGet();e.sendResponseHeaders(200,0);e.close();});server.start();
        try {
            String url="http://127.0.0.1:"+server.getAddress().getPort()+"/v1/models";
            assertThatThrownBy(()->http.request(url,"GET","test-key",null,false)).isInstanceOf(IntegrationException.class);
            assertThat(targetHits).hasValue(0);
        } finally {server.stop(0);}
    }
}
