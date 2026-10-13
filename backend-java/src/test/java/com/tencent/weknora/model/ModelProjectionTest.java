package com.tencent.weknora.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.security.AuthContext;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ModelProjectionTest {
    @Test void matchesProductionGoDtoAndLenientSecretScanAcrossRoles() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var fixtures = json.readTree(Files.readString(Path.of("src/test/java/com/tencent/weknora/model/go-model-fixtures.json")));
        assertThat(fixtures.size()).isEqualTo(131);
        for (var fixture : fixtures) {
            var row = json.convertValue(fixture.get("model"), new TypeReference<Map<String,Object>>() {});
            row.put("parameters", fixture.path("parameters_db").asText());
            AuthContext auth = new AuthContext("viewer", 1, fixture.path("role").asText(), fixture.path("system_admin").asBoolean(), null, false, Set.of(), Set.of());
            var repository = new ModelRepository(null, json, fixture.path("aes_key").asText());
            assertThat(json.readTree(json.writeValueAsBytes(repository.project(row, auth)))).as("Go DTO role=%s builtin=%s system=%s", auth.role(), row.get("is_builtin"), auth.systemAdmin()).isEqualTo(fixture.get("response"));
        }
    }
}
