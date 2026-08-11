package com.rpa.financial_intelligence_system.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class SecretCryptoService {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    public SecretCryptoService(@Value("${app.jwt-secret}") String masterSecret) {
        try { key = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(masterSecret.getBytes(StandardCharsets.UTF_8)), "AES"); }
        catch (Exception e) { throw new IllegalStateException("无法初始化密钥加密组件", e); }
    }
    public String encrypt(String value) {
        if (value == null || value.isBlank()) return null;
        try { byte[] iv=new byte[12]; random.nextBytes(iv); Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv)); byte[] encrypted=c.doFinal(value.getBytes(StandardCharsets.UTF_8)); byte[] result=new byte[iv.length+encrypted.length]; System.arraycopy(iv,0,result,0,iv.length);System.arraycopy(encrypted,0,result,iv.length,encrypted.length);return Base64.getEncoder().encodeToString(result); }
        catch(Exception e){throw new IllegalStateException("API Key 加密失败",e);}
    }
    public String decrypt(String value) {
        if(value==null||value.isBlank())return "";
        try {byte[] all=Base64.getDecoder().decode(value);byte[] iv=java.util.Arrays.copyOfRange(all,0,12);byte[] encrypted=java.util.Arrays.copyOfRange(all,12,all.length);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,iv));return new String(c.doFinal(encrypted),StandardCharsets.UTF_8);}
        catch(Exception e){throw new IllegalStateException("API Key 解密失败，请确认 JWT_SECRET 未被修改",e);}
    }
}
