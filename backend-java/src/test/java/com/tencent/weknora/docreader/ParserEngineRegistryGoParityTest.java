package com.tencent.weknora.docreader;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.docreader.ParserEngineRegistry;
import com.tencent.weknora.docreader.ParserEngineRegistry.*;
import java.nio.file.*;import java.util.*;
class ParserEngineRegistryGoParityTest {
@org.junit.jupiter.api.Test void registryMetadataAndMergeOrderMatchActualGo()throws Exception{
var json=new ObjectMapper();var fixtures=json.readTree(getClass().getResourceAsStream("/go-parser-registry.json"));
var registry=new ParserEngineRegistry((name,config)->{throw new AssertionError("unexpected external probe "+name);},new Availability(false,"anydoc engine not built into this binary (rebuild with `make build-anydoc` / `-tags anydoc`; Docker images need `--build-arg WITH_ANYDOC=1`)"));
int count=0;for(var c:fixtures){Map<String,String> overrides=c.path("overrides").isNull()?null:json.convertValue(c.get("overrides"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){});List<Engine> remote=c.path("remote").isNull()?null:json.convertValue(c.get("remote"),new com.fasterxml.jackson.core.type.TypeReference<List<Engine>>(){});
var actual=json.valueToTree(registry.list(c.get("connected").booleanValue(),overrides,remote));if(!actual.equals(c.get("expected")))throw new AssertionError(c.get("name")+" expected "+c.get("expected")+" actual "+actual);count++;}
System.out.println(count+" actual Go registry cases passed; external probe transport and HTTP endpoints pending");}}
