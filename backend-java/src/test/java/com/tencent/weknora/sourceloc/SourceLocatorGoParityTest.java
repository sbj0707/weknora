package com.tencent.weknora.sourceloc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.docreader.DocumentReadResult.SourceBlock;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Actual Go Index/TextBlocks/CleanQuote/MergeLocators output, including all HTML entity names. */
class SourceLocatorGoParityTest {
    final ObjectMapper json=new ObjectMapper();
    JsonNode fixture()throws Exception{return json.readTree(Path.of(System.getProperty("weknora.source.fixture","src/test/java/com/tencent/weknora/sourceloc/source-location-golden.json")).toFile());}
    List<Map<String,Object>> locators(JsonNode value){return value.isNull()?null:json.convertValue(value,new TypeReference<>(){});}
    void equal(Object actual,JsonNode expected,String message)throws Exception{
        assertThat(json.readTree(json.writeValueAsString(actual))).as(message).isEqualTo(expected);
    }
    List<SourceBlock> blocks(JsonNode input){var out=new ArrayList<SourceBlock>();for(var block:input)out.add(new SourceBlock(block.path("start").asLong(),block.path("end").asLong(),json.convertValue(block.path("locator"),new TypeReference<Map<String,Object>>(){})));return out;}
    List<Map<String,Object>> blockJson(List<SourceBlock> blocks){return blocks.stream().map(block->Map.<String,Object>of("start",block.start(),"end",block.end(),"locator",block.locator())).toList();}
    @Test void nativeRangeAndImagePositionsMatch235ActualGoIndexes()throws Exception{
        var indexes=fixture().path("indexes");assertThat(indexes.size()).isEqualTo(235);
        for(var item:indexes){var index=new SourceLocatorIndex(item.path("text").asText(),blocks(item.path("blocks")));String name=item.path("name").asText();
            equal(index.locators(item.path("start").asLong(),item.path("end").asLong()),item.get("expected"),name);
            for(var at:item.path("at"))equal(index.locatorsAt(at.path("position").asLong()),at.get("expected"),name+" at "+at.path("position"));}
    }
    @Test void paragraphsAndBomViewerOffsetsMatchActualGo()throws Exception{
        for(var item:fixture().path("texts")){
            equal(blockJson(SourceLocatorIndex.textBlocks(item.path("text").asText())),item.get("expected"),"paragraphs "+item.path("text"));
            equal(blockJson(SourceLocatorIndex.passThroughTextBlocks(item.path("text").asText())),item.get("pass"),"BOM "+item.path("text"));}
    }
    @Test void completeEntitySetAndFullUnicodeEvidenceQuotesMatchActualGo()throws Exception{
        var quotes=fixture().path("quotes");assertThat(quotes.size()).isEqualTo(4464);
        for(var item:quotes)assertThat(SourceLocatorIndex.cleanQuote(item.path("text").asText())).as(item.path("text").asText()).isEqualTo(item.path("expected").asText());
    }
    @Test void mergeDropsOnlyTheExactDuplicatesDefinedByGoWithoutMutatingSource()throws Exception{
        for(var item:fixture().path("merges")){
            var first=locators(item.path("First"));var second=locators(item.path("Second"));String before=json.writeValueAsString(first);
            equal(SourceLocatorIndex.mergeLocators(first,second),item.get("Expected"),"merge");assertThat(json.writeValueAsString(first)).isEqualTo(before);
        }
        var block=new SourceBlock(0,4,Map.of("type","pdf","bbox",new ArrayList<>(List.of(0.1,0.2)),"quote","original"));
        var index=new SourceLocatorIndex("word",List.of(block));index.locators(0,4).getFirst().put("quote","changed");assertThat(block.locator().get("quote")).isEqualTo("original");
    }
    List<SourceAlignment.Unit> units(JsonNode input){var out=new ArrayList<SourceAlignment.Unit>();if(input.isArray())for(var unit:input)out.add(new SourceAlignment.Unit(unit.path("Text").asText(),json.convertValue(unit.path("Locator"),new TypeReference<Map<String,Object>>(){})));return out;}
    @Test void fullTextRemappingAndAmbiguityChecksMatch39ActualGoRewrites()throws Exception{
        var remaps=fixture().path("remaps");assertThat(remaps.size()).isEqualTo(39);
        for(var item:remaps)equal(blockJson(SourceAlignment.remapBlocks(blocks(item.path("blocks")),item.path("old").asText(),item.path("new").asText())),item.get("expected"),"remap "+item.path("old"));
    }
    @Test void numericBoundariesAndStructuralAlignmentMatch88ActualGoCases()throws Exception{
        var alignments=fixture().path("alignments");assertThat(alignments.size()).isEqualTo(88);
        for(var item:alignments)equal(blockJson(SourceAlignment.align(item.path("markdown").asText(),units(item.path("units")))),item.get("expected"),"align "+item.path("markdown"));
    }
    @Test void csvRecordsAndRowLocationsMatch30ActualGoStructureCases()throws Exception{
        var cases=fixture().path("csv");assertThat(cases.size()).isEqualTo(30);
        for(var item:cases){var units=SourceAlignment.csvUnits(item.path("csv").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var rendered=units.stream().map(unit->Map.of("Text",unit.text(),"Locator",unit.locator())).toList();
            equal(rendered.isEmpty()?null:rendered,item.get("units"),"CSV structure "+item.path("csv"));
            equal(blockJson(SourceAlignment.align(item.path("markdown").asText(),units)),item.get("expected"),"CSV map "+item.path("csv"));
        }
    }

}
