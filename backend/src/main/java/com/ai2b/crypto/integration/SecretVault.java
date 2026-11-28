package com.ai2b.crypto.integration;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecretVault {
    private final String configured;
    private final Path file;
    private byte[] key;
    private final SecureRandom random = new SecureRandom();
    public SecretVault(@Value("${integrations.master-key:}") String configured,
            @Value("${integrations.key-file:${user.home}/.ai2b/integration.key}") String file) {
        this.configured = configured; this.file = Path.of(file);
    }
    private synchronized byte[] key(boolean create) throws Exception {
        if (key != null) return key;
        if (!configured.isBlank()) key = Base64.getDecoder().decode(configured.trim());
        else {
            if (!Files.exists(file)) {
                if (!create) throw new IllegalStateException("Missing encryption key");
                Files.createDirectories(file.toAbsolutePath().getParent());
                byte[] bytes = new byte[32]; random.nextBytes(bytes);
                Path staged = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
                Files.createFile(staged, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                try {
                    Files.writeString(staged, Base64.getEncoder().encodeToString(bytes), StandardOpenOption.WRITE);
                    // Publish an already-complete inode without overwriting an existing shared key.
                    try { Files.createLink(file, staged); }
                    catch (FileAlreadyExistsException ignored) { /* Read the existing key below. */ }
                } finally { Files.deleteIfExists(staged); }
            }
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.getPosixFilePermissions(file).stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"))) {
                throw new IllegalStateException("Key file must be private");
            }
            key = Base64.getDecoder().decode(Files.readString(file).trim());
        }
        if (key.length != 32) { key = null; throw new IllegalStateException("Invalid encryption key"); }
        return key;
    }
    public String encrypt(String kind, String plaintext) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(true), "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(kind.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) { throw new IntegrationException(503, "无法安全保存密钥，请检查服务端加密主密钥或私有密钥文件权限"); }
    }
    public String decrypt(String kind, String stored) {
        try {
            String[] parts = stored.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(false), "AES"), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            cipher.updateAAD(kind.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IntegrationException(503, "密钥无法解密，请恢复原加密主密钥；不会重新生成密钥覆盖已有配置"); }
    }
}
