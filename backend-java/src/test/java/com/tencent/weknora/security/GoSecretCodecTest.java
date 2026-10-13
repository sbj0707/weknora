package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class GoSecretCodecTest {
    private static final String KEY="0123456789abcdef0123456789abcdef";
    private final ObjectMapper json=new ObjectMapper();
    @Test void decryptsGoCiphertextAndRejectsMissingRotatedOrTamperedKeys() throws Exception {
        String ciphertext=json.readTree(Path.of("src/test/java/com/tencent/weknora/security/auth-golden.json").toFile()).path("encrypted_secret").asText();
        GoSecretCodec codec=new GoSecretCodec(KEY);
        assertEquals("Go secret 密钥 🔐",codec.decryptStrict(ciphertext));
        assertEquals("Go secret 密钥 🔐",codec.decryptStrict(codec.encryptLegacyWhenConfigured("Go secret 密钥 🔐")));
        assertEquals(ciphertext,codec.encryptLegacyWhenConfigured(ciphertext));
        assertEquals("legacy",new GoSecretCodec("").encryptLegacyWhenConfigured("legacy"));
        assertEquals("legacy",new GoSecretCodec("").decryptStrict("legacy"));
        assertThrows(ApiException.class,()->new GoSecretCodec("").decryptStrict(ciphertext));
        assertEquals("",new GoSecretCodec("abcdef0123456789abcdef0123456789").decryptLenient(ciphertext));
        assertThrows(ApiException.class,()->codec.decryptStrict("enc:v1:broken"));
        char replacement=ciphertext.charAt(10)=='A'?'B':'A';
        assertThrows(ApiException.class,()->codec.decryptStrict(ciphertext.substring(0,10)+replacement+ciphertext.substring(11)));
    }
    @Test void everySupportedEnvironmentStorageConfigMatchesRealGoAndUnsupportedFailsClosed() throws Exception {
        var golden=json.readTree(Path.of("src/test/java/com/tencent/weknora/security/auth-golden.json").toFile()).path("storage_configs");
        MockEnvironment env=new MockEnvironment();
        for(String provider:List.of("MINIO","COS","TOS","S3","OSS","OBS"))for(String field:List.of("ENDPOINT","REGION","ACCESS_KEY","ACCESS_KEY_ID","SECRET_ACCESS_KEY","SECRET_KEY","SECRET_ID","BUCKET_NAME","APP_ID","PATH_PREFIX","TEMP_BUCKET_NAME","TEMP_REGION","USE_SSL","FORCE_PATH_STYLE"))env.setProperty(provider+"_"+field,"test-"+field);
        env.setProperty("S3_USE_SSL","true");env.setProperty("S3_FORCE_PATH_STYLE","true");env.setProperty("MINIO_USE_SSL","true");env.setProperty("OBS_USE_SSL","false");env.setProperty("LOCAL_STORAGE_PATH_PREFIX"," /fixture/path ");
        for(String provider:List.of("local","minio","cos","tos","s3","oss","obs")) {
            env.setProperty("STORAGE_TYPE",provider);
            assertEquals(golden.path(provider),json.valueToTree(RegistrationStorageSnapshot.from(env,new GoSecretCodec("")).config()),provider);
        }
        env.setProperty("STORAGE_TYPE","ks3");assertThrows(ApiException.class,()->RegistrationStorageSnapshot.from(env,new GoSecretCodec("")));
        env.setProperty("STORAGE_TYPE","s3");var encrypted=RegistrationStorageSnapshot.from(env,new GoSecretCodec(KEY));
        assertEquals("test-ACCESS_KEY",new GoSecretCodec(KEY).decryptStrict(encrypted.config().get("access_key_id").toString()));
        assertEquals("test-SECRET_KEY",new GoSecretCodec(KEY).decryptStrict(encrypted.config().get("secret_access_key").toString()));
    }
}
