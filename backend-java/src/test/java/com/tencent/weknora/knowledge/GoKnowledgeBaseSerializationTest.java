package com.tencent.weknora.knowledge;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
class GoKnowledgeBaseSerializationTest {
    @Test void preservesGoTypedJsonDefaultsNullsOmissionsCapabilitiesAndSecretRedaction() throws Exception {
        var mapper=new ObjectMapper();
        var repository=new KnowledgeBaseRepository(new JdbcTemplate(),mapper,"postgres");
        try(var input=getClass().getResourceAsStream("/go-kb-cases.json")) {
            for(var fixture:mapper.readTree(input)) {
                Map<String,Object> db=mapper.convertValue(fixture.path("db"),new TypeReference<>(){});
                var actual=repository.project(db);
                actual.remove(java.util.List.of("vector_store_name","vector_store_source","vector_store_status","vector_store_engine_type"));
                var expected=(com.fasterxml.jackson.databind.node.ObjectNode)fixture.get("go").deepCopy();
                // Service display normalization removes a legacy pointer-to-empty binding.
                if(expected.path("vector_store_id").asText().isEmpty())expected.remove("vector_store_id");
                assertThat(actual).as("Go JSON for %s",fixture.path("go").path("type").asText()).isEqualTo(expected);
            }
        }
    }
    @Test void nativeCreateDecodesAndDefaultsActualGoPayloads() throws Exception {
        var mapper=new ObjectMapper();var codec=new KnowledgeBaseCodec(mapper);
        try(var input=getClass().getResourceAsStream("/go-kb-cases.json")) {
            for(var fixture:mapper.readTree(input)) {
                var kb=codec.fromPayload((com.fasterxml.jackson.databind.node.ObjectNode)fixture.get("input"));codec.ensureDefaults(kb);
                assertThat(codec.render(kb)).as("Go payload %s",fixture.get("input")).isEqualTo(fixture.get("go"));
            }
        }
    }
}
