package com.tencent.weknora.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Go utils.EncryptAESGCM format: enc:v1: + base64url(nonce || ciphertext || tag). */
@Component
public class GoSecretCodec {
    private static final String PREFIX="enc:v1:";
    private final byte[] key;
    @Autowired public GoSecretCodec(Environment environment) { this(environment.getProperty("SYSTEM_AES_KEY", "")); }
    public GoSecretCodec(String key) {
        byte[] bytes=(key==null?"":key).getBytes(StandardCharsets.UTF_8);
        this.key=bytes.length==32?bytes:null;
    }
    public String encryptLegacyWhenConfigured(String plaintext) {
        if (plaintext==null || plaintext.isEmpty() || plaintext.startsWith(PREFIX) || key==null) return plaintext;
        try {
            byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            byte[] encrypted=cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined=Arrays.copyOf(nonce,nonce.length+encrypted.length);System.arraycopy(encrypted,0,combined,nonce.length,encrypted.length);
            return PREFIX+Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
        } catch(Exception ex) {throw new IllegalStateException("Stored credential encryption failed",ex);}
    }
    public String decryptStrict(String stored) {
        if(stored==null || !stored.startsWith(PREFIX))return stored;
        if(key==null)throw cryptoError();
        try {
            byte[] combined=Base64.getUrlDecoder().decode(stored.substring(PREFIX.length()));
            if(combined.length<28)throw cryptoError();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOf(combined,12)));
            return new String(cipher.doFinal(combined,12,combined.length-12),StandardCharsets.UTF_8);
        }catch(ApiException ex){throw ex;}catch(Exception ex){throw cryptoError();}
    }
    public String decryptLenient(String stored) {
        try{return decryptStrict(stored);}catch(ApiException ex){return "";}
    }
    private static ApiException cryptoError(){return new ApiException(503,"CRYPTO_CONFIGURATION","Encrypted credential is unavailable; check SYSTEM_AES_KEY").withAppCode(1008);}
}
