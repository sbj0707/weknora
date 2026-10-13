package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RuntimeCatalogTest {
    @Test void productionGoCatalogResolutionGoldenContracts() throws Exception {
        ObjectMapper json=CatalogRuntime.JSON;JsonNode cases=json.readTree(Files.readString(Path.of("src/test/java/com/tencent/weknora/model/go-runtime-fixtures.json")));
        CatalogRuntime baseline=CatalogRuntime.builtins();
        assertThat(cases.size()).isGreaterThan(600);
        for(JsonNode fixture:cases) {
            CatalogRuntime runtime=baseline;String deployment=fixture.path("deployment").asText(),console=fixture.path("console").asText();
            if(!deployment.isEmpty())runtime=CatalogOverlay.apply(runtime,deployment,null,false);
            if(!console.isEmpty())runtime=CatalogOverlay.apply(runtime,console,null,true);
            JsonNode input=fixture.path("input");
            CatalogRuntime.Ref ref=new CatalogRuntime.Ref(input.path("provider").asText(),input.path("model").asText(),input.path("base_url").asText(),input.path("model_type").asText(),input.path("extra").isObject()?(ObjectNode)input.get("extra"):json.createObjectNode(),input.path("spec").isObject()?(ObjectNode)input.get("spec"):null,input.path("truncate_prompt_tokens").asLong());
            String label=ref.provider()+"/"+ref.model()+" type="+ref.type()+" url="+ref.baseURL()+" extra="+ref.extra();
            if(fixture.has("error")) {
                CatalogRuntime candidate=runtime;assertThatThrownBy(()->candidate.resolve(ref)).as(label).isInstanceOf(IllegalArgumentException.class).hasMessage(fixture.path("error").asText());
            } else {
                JsonNode actual=json.readTree(json.writeValueAsBytes(runtime.resolve(ref).fixture()));
                assertThat(actual).as(label).isEqualTo(fixture.get("result"));
            }
        }
    }
}
