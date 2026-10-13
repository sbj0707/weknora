package com.tencent.weknora.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class TenantInvitationCodecCompatibilityTest {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    @TestFactory Stream<DynamicTest> actualGoPrivateHelperGoldens()throws Exception{
        JsonNode fixtures=JSON.readTree(Path.of("src/test/java/com/tencent/weknora/security/invitation-codec-golden.json").toFile());List<DynamicTest> tests=new ArrayList<>();
        for(JsonNode item:fixtures.path("ttls"))tests.add(DynamicTest.dynamicTest("TTL "+item.path("raw").asText(),()->assertEquals(item.path("nanos").asLong(),TenantInvitationCodec.ttl(item.path("raw").asText()).toNanos())));
        for(JsonNode item:fixtures.path("urls"))tests.add(DynamicTest.dynamicTest("Invite URL "+item.path("configured").asText()+" / "+item.path("environment").asText()+" / "+item.path("token").asText(),()->assertEquals(item.path("output").asText(),TenantInvitationCodec.inviteUrl(item.path("configured").asText(),item.path("environment").asText(),item.path("token").asText()))));
        for(JsonNode item:fixtures.path("rows"))tests.add(DynamicTest.dynamicTest("DTO "+item.path("name").asText(),()->{
            Map<String,Object> row=JSON.convertValue(item.path("row"),new TypeReference<>(){});
            Map<String,Map<String,Object>> users=JSON.convertValue(item.path("users"),new TypeReference<>(){});
            Map<Long,Map<String,Object>> tenants=JSON.convertValue(item.path("tenants"),new TypeReference<>(){});
            assertEquals(item.path("output"),JSON.readTree(JSON.writeValueAsBytes(TenantInvitationCodec.project(row,users,tenants,false,"",""))));
        }));return tests.stream();
    }
}
