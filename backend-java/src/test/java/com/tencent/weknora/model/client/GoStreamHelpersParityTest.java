package com.tencent.weknora.model.client;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GoStreamHelpersParityTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static JsonNode fixtures()throws Exception{return JSON.readTree(Files.readString(Path.of("src/test/java/com/tencent/weknora/model/client/go-stream-fixtures.json")));}
    @Test void actualGoIncrementalFieldExtractionAcrossEveryFragmentBoundary()throws Exception{
        int count=0;for(JsonNode f:fixtures().path("extractors")){JSONFieldExtractor x=new JSONFieldExtractor(f.path("field").asText());for(int i=0;i<f.path("fragments").size();i++){assertThat(x.feed(f.path("fragments").get(i).asText())).as("case %s fragment %s",count,i).isEqualTo(f.path("outputs").get(i).asText());assertThat(x.isDone()).as("case %s done %s",count,i).isEqualTo(f.path("done").get(i).asBoolean());}count++;}assertThat(count).isEqualTo(52);
    }
    @Test void actualGoSandboxWriteAndEditProgressIncludingPartialJSON()throws Exception{
        int count=0;for(JsonNode f:fixtures().path("progress")){SandboxFileProgress p=new SandboxFileProgress(f.path("tool").asText(),()->1L);for(int i=0;i<f.path("fragments").size();i++){ObjectNode out=p.feed(f.path("fragments").get(i).asText());assertThat((JsonNode)JSON.valueToTree(out)).as("case %s fragment %s",count,i).isEqualTo(f.path("outputs").get(i));}count++;}assertThat(count).isEqualTo(12);
    }
    @Test void actualGoEventOrderingGappedCallsThoughtAndFileProgress()throws Exception{
        int count=0;for(JsonNode f:fixtures().path("assemblies")){List<ObjectNode> events=new ArrayList<>();try(var context=new CallContext()){ModelStreamAssembler a=new ModelStreamAssembler(context,events::add,ModelClientResponsesTest.endpoint("http://localhost",JSON.createObjectNode()));for(JsonNode d:f.path("deltas")){List<ModelStreamAssembler.ToolDelta> fragments=new ArrayList<>();for(JsonNode t:d.path("ToolCalls"))fragments.add(new ModelStreamAssembler.ToolDelta(t.path("Index").asInt(),t.path("ID").asText(),t.path("Type").asText(),t.path("Name").asText(),t.path("Arguments").asText()));a.process(d.path("Reasoning").asText(),d.path("Content").asText(),fragments,d.path("FinishReason").asText());}a.endAtEOF();assertThat((JsonNode)JSON.valueToTree(events)).as("assembly %s",count).isEqualTo(f.path("events"));}count++;}assertThat(count).isEqualTo(7);
    }
    @Test void sandboxByteProgressIsThrottledButLineChangesEmitImmediately(){
        long[] clock={1};SandboxFileProgress p=new SandboxFileProgress("write_sandbox_file",()->clock[0]);assertThat(p.feed("{\"path\":\"x\",\"content\":\"a")).isNotNull();assertThat(p.feed("b")).isNull();clock[0]+=SandboxFileProgress.INTERVAL;assertThat(p.feed("c").path("bytes").asInt()).isEqualTo(3);assertThat(p.feed("\\nnext").path("added_lines").asInt()).isEqualTo(2);
    }
}
