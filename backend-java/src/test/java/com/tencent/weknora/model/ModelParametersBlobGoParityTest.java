package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.security.ApiException;
import com.tencent.weknora.security.AuthContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Reads actual Go model-repository BLOB parameters through the native Java factory. */
class ModelParametersBlobGoParityTest {
    @Test void productionGoSQLiteBlobParametersPreserveWireAndStrictCredentials() throws Exception {
        JsonNode cases = NativeModelClientsTest.JSON.readTree(Files.readString(Path.of(
            System.getProperty("weknora.model.blob.fixture",
                "src/test/java/com/tencent/weknora/model/client/go-model-blob.json"))));
        try (var h = new NativeModelClientsTest.Harness()) {
            h.jdbc.update("INSERT INTO tenants(id,name,business) VALUES(7,'caller','fixture'),(8,'owner','fixture')");
            h.fixture = NativeModelClientsTest.JSON.createObjectNode().put("kind", "embedding");
            int count = 0, requests = 0;
            for (JsonNode fixture : cases) {
                h.requests.clear();
                String id = fixture.path("id").asText() + "-" + count;
                long owner = fixture.path("owner").asLong();
                ObjectNode parameters = (ObjectNode) fixture.path("parameters").deepCopy();
                parameters.put("base_url", parameters.path("base_url").asText().replace("{base}", h.url()));
                h.seed(id, owner, false, "active", "unknown", "Embedding", parameters);
                h.jdbc.update("UPDATE models SET parameters=? WHERE id=?", parameters.toString().getBytes(StandardCharsets.UTF_8), id);
                assertThat(h.jdbc.queryForObject("SELECT typeof(parameters) FROM models WHERE id=?", String.class, id))
                    .isEqualTo(fixture.path("sqlite_type").asText()).isEqualTo("blob");
                assertThat(h.jdbc.queryForMap("SELECT parameters FROM models WHERE id=?", id).get("parameters")).isInstanceOf(byte[].class);
                AuthContext ownerContext = new AuthContext("viewer", owner, "viewer", false, null, false, Set.of(), Set.of());
                try (var call = CallContext.timeout(Duration.ofSeconds(5))) {
                    float[] vector = h.factory.embedding(ownerContext, id).embed(call, fixture.path("query").asText(), true);
                    NativeModelClientsTest.equalJSON(vector, fixture.path("result"), "Go BLOB vector " + id);
                    NativeModelClientsTest.equalJSON(h.requests, fixture.path("requests"), "Go BLOB HTTP " + id);
                }
                requests += h.requests.size(); count++;
            }
            assertThat(count).isEqualTo(6); assertThat(requests).isEqualTo(6);
            h.requests.clear();
            ObjectNode broken = NativeModelClientsTest.JSON.createObjectNode().put("provider", "generic")
                .put("base_url", h.url()+"/v1").put("api_key", "enc:v1:invalid");
            h.seed("broken-blob",1,false,"active","unknown","Embedding",broken);
            h.jdbc.update("UPDATE models SET parameters=? WHERE id='broken-blob'", broken.toString().getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> h.factory.embedding(h.viewer,"broken-blob")).isInstanceOf(ApiException.class);
            h.jdbc.update("UPDATE models SET parameters=? WHERE id='broken-blob'", new byte[]{'{'});
            assertThatThrownBy(() -> h.factory.embedding(h.viewer,"broken-blob"))
                .isInstanceOf(ApiException.class).hasMessage("Invalid persisted model parameters");
            assertThat(h.requests).isEmpty();
        }
    }
}
