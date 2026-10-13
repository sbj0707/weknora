package com.tencent.weknora.docreader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ParserConfigurationCompatibilityTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private JsonNode fixtures() throws Exception {
        try (var input=getClass().getResourceAsStream("/go-parser-config.json")) {return JSON.readTree(input);}
    }
    @Test void actualGoKnowledgeAndChatRouting() throws Exception {
        JsonNode cases=fixtures().path("routing");assertEquals(320,cases.size());
        for (JsonNode test:cases) {
            ParserConfiguration config=test.path("prefer").asText().isEmpty()?new ParserConfiguration():
                new ParserConfiguration(ft->List.of("docx","pptx","xlsx").contains(ft)?"anydoc":"");
            List<ParserConfiguration.Rule> rules=ParserConfiguration.rules(test.path("rules"));
            String fileType=test.path("file_type").asText(),name=test.path("name").asText();
            assertEquals(test.path("default").asText(),config.defaultEngine(fileType),name);
            assertEquals(test.path("knowledge").asText(),config.resolveKnowledgeEngine(rules,fileType),name);
            assertEquals(test.path("chat").asText(),config.resolveChatEngine(rules,fileType),name);
            ParserConfiguration.Rule actual=config.resolveKnowledgeRule(rules,fileType);
            if (test.path("rule").isNull()) assertNull(actual,name);
            else {
                var array=JSON.createArrayNode().add(test.path("rule"));
                assertEquals(ParserConfiguration.rules(array).getFirst(),actual,name);
            }
        }
    }
    @Test void actualGoTenantOverrides() throws Exception {
        JsonNode cases=fixtures().path("overrides");assertEquals(106,cases.size());
        for (JsonNode test:cases) {
            Map<String,String> actual=ParserConfiguration.tenantOverrides(test.path("config"));
            assertEquals(test.path("overrides"),JSON.valueToTree(actual),test.path("name").asText());
        }
    }
    @Test void xlsxRuleOverrideGatesAndCopyIsolation() {
        var config=new ParserConfiguration();
        for (String type:List.of("xlsx",".XLS","\u00a0xlsx\u3000")) for (String engine:List.of("","builtin"," builtin ","markitdown")) {
            List<ParserConfiguration.Rule> rules=List.of(new ParserConfiguration.Rule(List.of("xlsx","xls"),engine,false));
            Map<String,String> original=Map.of("endpoint","tenant","xlsx_first_row_as_header","true");
            Map<String,String> upload=Map.of("endpoint","","extra","upload");
            Map<String,String> merged=ParserConfiguration.mergeOverrides(original,upload);
            config.applyKnowledgeRuleOverrides(merged,rules,type);
            assertEquals("",merged.get("endpoint"));assertEquals("upload",merged.get("extra"));
            assertEquals(engine.equals("markitdown")?"true":"false",merged.get("xlsx_first_row_as_header"));
            assertEquals("true",original.get("xlsx_first_row_as_header"));
        }
        Map<String,String> merged=ParserConfiguration.mergeOverrides(null,null);
        config.applyKnowledgeRuleOverrides(merged,List.of(new ParserConfiguration.Rule(List.of("xlsx"),"builtin",null)),"xlsx");
        assertTrue(merged.isEmpty());
        config.applyKnowledgeRuleOverrides(merged,List.of(new ParserConfiguration.Rule(List.of("pdf"),"builtin",true)),"pdf");
        assertTrue(merged.isEmpty());
    }
    @Test void nullableRuleElementsAndTypedConfigRejection() throws Exception {
        var rules=ParserConfiguration.rules(JSON.readTree("[null,{\"file_types\":[null,\"txt\"],\"engine\":null}]"));
        assertEquals("",new ParserConfiguration().resolveChatEngine(rules,""));
        assertEquals(List.of("","txt"),rules.get(1).fileTypes());
        assertThrows(IllegalArgumentException.class,()->ParserConfiguration.tenantOverrides(JSON.readTree("{\"mineru_enable_table\":\"false\"}")));
        assertThrows(IllegalArgumentException.class,()->ParserConfiguration.rules(JSON.readTree("[{\"file_types\":[1]}]")));
        assertThrows(IllegalArgumentException.class,()->ParserConfiguration.rules(JSON.readTree("[{\"xlsx_first_row_as_header\":1}]")));
    }
}
