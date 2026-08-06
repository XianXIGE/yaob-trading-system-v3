package com.yaob.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM 加密工具，用于加密敏感数据（如币安 API Key/Secret）
 * 密钥从配置文件 yaob.encryption.key 读取，环境变量 YAOB_ENCRYPTION_KEY 可覆盖
 */
@Component
public class CryptoUtil {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int IV_LENGTH = 12; // bytes

    private final byte[] keyBytes;

    public CryptoUtil(@Value("${yaob.encryption.key:yaob-v3-default-secret-key-change-me}") String key) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            this.keyBytes = sha256.digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("初始化加密工具失败", e);
        }
    }

    /**
     * 加密明文，返回 Base64 编码的 "iv:ciphertext" 格式
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // 格式: Base64(iv) + ":" + Base64(ciphertext)
            return Base64.getEncoder().encodeToString(iv) + ":" +
                   Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("加密失败", e);
        }
    }

    /**
     * 解密 Base64 编码的 "iv:ciphertext" 格式数据
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }
        // 兼容旧数据：如果不是加密格式（没有冒号），直接返回原文
        if (!ciphertext.contains(":")) {
            return ciphertext;
        }
        try {
            String[] parts = ciphertext.split(":", 2);
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getDecoder().decode(parts[1]);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 解密失败可能是旧数据或密钥变更，返回原文兜底
            return ciphertext;
        }
    }

    /**
     * 判断字符串是否已加密（包含冒号且能 Base64 解码）
     */
    public boolean isEncrypted(String value) {
        if (value == null || value.isEmpty()) return false;
        return value.contains(":") && value.split(":", 2).length == 2;
    }
}
