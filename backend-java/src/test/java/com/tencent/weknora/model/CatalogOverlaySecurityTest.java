package com.tencent.weknora.model;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CatalogOverlaySecurityTest {
    @Test void consoleRejectsRoutingCredentialsDuplicatesAndUnknownCompatWithoutMutation() {
        CatalogRuntime baseline=CatalogRuntime.builtins();
        for(String overlay:new String[]{
            "{\"providers\":{\"openai\":{\"api_key\":\"secret\"}}}",
            "{\"providers\":{\"openai\":{\"base_url\":\"https://other.invalid\"}}}",
            "{\"providers\":{\"openai\":{\"headers\":{\"Authorization\":\"secret\"}}}}",
            "{\"providers\":{\"openai\":{\"models\":[{\"id\":\"gpt-5\",\"context_window\":-1}]}}}",
            "{\"providers\":{\"openai\":{\"compat\":{\"unsupported_typo\":true}}}}",
            "{\"providers\":{\"openai\":{},\"openai\":{}}}",
            "{\"providers\":{\"OpenAI\":{},\" openai \":{}}}",
            "{\"providers\":{\"openai\":{\"icon\":\"../secret\"}}}",
            "{\"providers\":{}} {\"providers\":{}}"
        })assertThatThrownBy(()->CatalogOverlay.apply(baseline,overlay,null,true)).as(overlay).isInstanceOf(IllegalArgumentException.class);
        assertThat(baseline.find(baseline.providers.get("openai"),"gpt-5","KnowledgeQA").path("context_window").asLong()).isEqualTo(400000);
    }
    @Test void deploymentLoadsOnlySvgInsideOverlayDirectoryAndRejectsEscapingSymlink() throws Exception {
        Path directory=Files.createTempDirectory("weknora-catalog-icon-");Path outside=Files.createTempFile("weknora-catalog-outside-",".svg");
        try {
            Files.writeString(directory.resolve("icon.svg"),"<?xml version=\"1.0\"?><!-- sample --><svg xmlns=\"http://www.w3.org/2000/svg\"/>");
            CatalogRuntime runtime=CatalogOverlay.apply(CatalogRuntime.builtins(),"{\"providers\":{\"generic\":{\"icon\":\"icon.svg\"}}}",directory,false);
            assertThat(runtime.providers.get("generic").definition().path("Icon").asText()).isNotEmpty();
            Files.writeString(outside,"<svg/>");Files.createSymbolicLink(directory.resolve("outside.svg"),outside);
            assertThatThrownBy(()->CatalogOverlay.apply(runtime,"{\"providers\":{\"generic\":{\"icon\":\"outside.svg\"}}}",directory,false)).isInstanceOf(IllegalArgumentException.class);
            Files.writeString(directory.resolve("secret.txt"),"not-svg-secret");
            assertThatThrownBy(()->CatalogOverlay.apply(runtime,"{\"providers\":{\"generic\":{\"icon\":\"secret.txt\"}}}",directory,false)).isInstanceOf(IllegalArgumentException.class);
        }finally {Files.deleteIfExists(directory.resolve("outside.svg"));Files.deleteIfExists(directory.resolve("icon.svg"));Files.deleteIfExists(directory.resolve("secret.txt"));Files.deleteIfExists(directory);Files.deleteIfExists(outside);}
    }
}
