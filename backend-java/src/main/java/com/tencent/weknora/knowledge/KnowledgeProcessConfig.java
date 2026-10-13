package com.tencent.weknora.knowledge;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.docreader.ParserConfiguration;
import com.tencent.weknora.security.ApiException;
import java.io.*;
import java.util.*;

/** Effective upload process configuration, preserving Go zero/omitted-field and snapshot merge semantics. */
public final class KnowledgeProcessConfig {
    private final ObjectMapper json;private final JsonNode schemas;
    public KnowledgeProcessConfig(ObjectMapper json){this.json=json;try(var input=KnowledgeProcessConfig.class.getResourceAsStream("/knowledge-process-schema.json")){if(input==null)throw new IllegalStateException("Missing knowledge process schema");schemas=json.readTree(input);}catch(IOException ex){throw new UncheckedIOException(ex);}}
    KnowledgeProcessConfig(ObjectMapper json,JsonNode schemas){this.json=json;this.schemas=schemas;}
    public ObjectNode resolve(JsonNode kb,JsonNode input){ObjectNode override=canonical("overrides",input);ObjectNode chunk=canonical("chunking",kb.path("chunking_config")),vlm=canonical("vlm",kb.path("vlm_config")),asr=canonical("asr",kb.path("asr_config")),question=canonical("question",kb.path("question_generation_config")),extract=canonical("extract",kb.path("extract_config"));
        ObjectNode actions=defaultImageActions();JsonNode image=kb.path("image_processing_config");ObjectNode custom=canonical("image_actions",image.path("image_actions"));if(nonemptyArray(custom.path("ocr").path("on")))actions=custom;
        boolean multimodel=vlmEnabled(vlm),graph=kb.path("indexing_strategy").path("graph_enabled").asBoolean()&&extract.path("enabled").asBoolean(),summary=true,imageAttrs=image.path("image_attrs_enabled").asBoolean();
        if(input!=null&&!input.isNull()&&!input.isMissingNode()){
            if(override.has("summary_enabled"))summary=override.path("summary_enabled").asBoolean();
            if(override.has("chunking_config")){JsonNode value=override.get("chunking_config");mergeNonzero(chunk,value,List.of("chunk_size","chunk_overlap","parent_chunk_size","child_chunk_size","strategy","token_limit","table_metadata_instructions"));mergeArrays(chunk,value,List.of("separators","parser_engine_rules","languages"));if(value.path("enable_parent_child").asBoolean())chunk.put("enable_parent_child",true);else chunk.remove("enable_parent_child");}
            if(nonemptyArray(override.path("parser_engine_rules")))chunk.set("parser_engine_rules",override.get("parser_engine_rules").deepCopy());
            if(override.has("enable_multimodel"))multimodel=override.path("enable_multimodel").asBoolean();
            if(override.has("vlm_config")){ObjectNode value=(ObjectNode)override.get("vlm_config").deepCopy();inheritString(value,vlm,"description_language");inheritString(value,vlm,"custom_instructions");vlm=value;}
            if(override.has("asr_config"))asr=(ObjectNode)override.get("asr_config").deepCopy();
            if(override.has("question_generation_config")){ObjectNode value=(ObjectNode)override.get("question_generation_config").deepCopy();inheritString(value,question,"custom_instructions");question=value;}
            if(override.has("graph_enabled"))graph=override.path("graph_enabled").asBoolean();
            if(override.has("image_attrs_enabled"))imageAttrs=override.path("image_attrs_enabled").asBoolean();
            if(nonemptyArray(override.path("image_actions").path("ocr").path("on")))actions=canonical("image_actions",override.get("image_actions"));
            if(override.has("extract_config")){JsonNode value=override.get("extract_config");extract.put("enabled",value.path("enabled").asBoolean());mergeNonzero(extract,value,List.of("text","custom_instructions"));mergeArrays(extract,value,List.of("tags","nodes","relations"));}
            graph=graph&&extract.path("enabled").asBoolean();
        }
        ObjectNode result=json.createObjectNode();result.put("summary_enabled",summary);result.set("chunking_config",chunk);result.put("enable_multimodel",multimodel);result.set("vlm_config",vlm);result.set("asr_config",asr);result.set("question_generation_config",question);result.put("graph_enabled",graph);result.put("image_attrs_enabled",imageAttrs);result.set("image_actions",actions);result.set("extract_config",extract);return result;
    }
    private static final Set<String> IMPORT=Set.of("pdf","txt","docx","doc","epub","html","htm","mhtml","md","markdown","xmind","png","jpg","jpeg","gif","csv","xlsx","xls","pptx","ppt","json","mp3","wav","m4a","flac","ogg");
    private static final Set<String> IMAGES=Set.of("jpg","jpeg","png","gif","webp","bmp","svg","tiff");
    private static final Set<String> AUDIO=Set.of("mp3","wav","m4a","flac","ogg");
    private static final Set<String> VIDEO=Set.of("mp4","mov","avi","mkv","webm","wmv","flv");
    public static String fileType(String filename){int dot=filename.lastIndexOf('.');return dot<0?"unknown":filename.substring(dot+1);}
    public static boolean supported(String fileType){return IMPORT.contains(ParserConfiguration.normalizeFileType(fileType));}
    public static boolean dataTable(String fileType){return Set.of("csv","xlsx","xls").contains(ParserConfiguration.normalizeFileType(fileType));}
    public static boolean video(String fileType){return VIDEO.contains(fileType.toLowerCase(Locale.ROOT));}
    public static void validateImportType(String type){String normalized=ParserConfiguration.normalizeFileType(type);if(normalized.isEmpty()||normalized.equals("unknown"))throw bad("无法确定文件类型");if(video(normalized))throw bad("暂不支持上传视频文件");if(!supported(normalized))throw bad("不支持的文件类型: "+normalized);}
    public ObjectNode resolveFileImport(JsonNode kb,JsonNode overrides,Boolean enableMultimodel,String fileType){validateImportType(fileType);ObjectNode result=resolve(kb,overrides);if(enableMultimodel!=null&&(overrides==null||overrides.path("enable_multimodel").isNull()||overrides.path("enable_multimodel").isMissingNode()))result.put("enable_multimodel",enableMultimodel);
        if(overrides!=null&&!overrides.isNull()&&!overrides.isMissingNode())validateProcessOverrides(kb,overrides,List.of(fileType));else{String normalized=ParserConfiguration.normalizeFileType(fileType);if(IMAGES.contains(normalized)&&!vlmEnabled(result.path("vlm_config")))throw bad("上传图片文件需要设置VLM模型");if(AUDIO.contains(normalized)&&!asrEnabled(kb.path("asr_config")))throw bad("上传音频文件需要设置ASR语音识别模型");}return result;}
    public void validateProcessOverrides(JsonNode kb,JsonNode overrides,List<String> fileTypes){if(overrides==null||overrides.isNull()||overrides.isMissingNode())return;ObjectNode result=resolve(kb,overrides);boolean image=fileTypes.stream().anyMatch(IMAGES::contains),audio=fileTypes.stream().anyMatch(type->AUDIO.contains(type.toLowerCase(Locale.ROOT)));if(image&&!vlmEnabled(result.path("vlm_config")))throw bad("上传图片文件需要设置VLM模型");if(audio&&!asrEnabled(result.path("asr_config")))throw bad("上传音频文件需要设置ASR语音识别模型");
        Map<String,JsonNode> instructions=new LinkedHashMap<>();instructions.put("table metadata instructions",result.path("chunking_config").path("table_metadata_instructions"));instructions.put("image instructions",result.path("vlm_config").path("custom_instructions"));instructions.put("question generation instructions",result.path("question_generation_config").path("custom_instructions"));instructions.put("graph extraction instructions",result.path("extract_config").path("custom_instructions"));for(var entry:instructions.entrySet()){String text=entry.getValue().asText();if(text.codePointCount(0,text.length())>4000)throw bad(entry.getKey()+" exceeds 4000 characters");}}
    private static ApiException bad(String message){return new ApiException(400,"BAD_REQUEST",message).withAppCode(1000);}
    public static boolean vlmEnabled(JsonNode value){return value.path("enabled").asBoolean()&&!value.path("model_id").asText().isEmpty()||!value.path("model_name").asText().isEmpty()&&!value.path("base_url").asText().isEmpty();}
    public static boolean asrEnabled(JsonNode value){return value.path("enabled").asBoolean()&&!value.path("model_id").asText().isEmpty();}
    public ObjectNode normalizeOverrides(JsonNode input){return canonical("overrides",input);}
    private ObjectNode canonical(String kind,JsonNode input){return (ObjectNode)normalize(schemas.path(kind),input);}
    private JsonNode normalize(JsonNode schema,JsonNode input){String kind=schema.path("kind").asText();if(kind.equals("pointer")){if(input==null||input.isNull()||input.isMissingNode())return NullNode.instance;return normalize(schema.get("elem"),input);}
        if(kind.equals("struct")){if(input!=null&&!input.isNull()&&!input.isMissingNode()&&!input.isObject())throw new IllegalArgumentException("Expected object process configuration");ObjectNode result=json.createObjectNode();for(var field:schema.path("fields")){String name=field.path("name").asText();JsonNode supplied=input==null?null:input.get(name);if(supplied==null&&input!=null&&input.isObject()){var names=input.fieldNames();while(names.hasNext()){String candidate=names.next();if(candidate.equalsIgnoreCase(name)){supplied=input.get(candidate);break;}}}JsonNode value=normalize(field.get("schema"),supplied);if(!field.path("omitempty").asBoolean()||!empty(value,field.get("schema")))result.set(name,value);}return result;}
        boolean nil=input==null||input.isNull()||input.isMissingNode();if(kind.equals("slice")||kind.equals("array")){if(nil)return NullNode.instance;ArrayNode result=json.createArrayNode();if(!input.isArray())throw new IllegalArgumentException("Expected array process configuration");for(var element:input)result.add(normalize(schema.get("elem"),element));return result;}
        if(kind.equals("map")){if(nil)return NullNode.instance;ObjectNode result=json.createObjectNode();if(!input.isObject())throw new IllegalArgumentException("Expected object process configuration");input.fields().forEachRemaining(entry->result.set(entry.getKey(),normalize(schema.get("elem"),entry.getValue())));return result;}
        if(kind.equals("bool")){if(!nil&&!input.isBoolean())throw new IllegalArgumentException("Expected boolean process configuration");return BooleanNode.valueOf(!nil&&input.booleanValue());}
        if(kind.equals("string")){if(!nil&&!input.isTextual())throw new IllegalArgumentException("Expected string process configuration");return TextNode.valueOf(nil?"":input.textValue());}
        if(kind.startsWith("int")||kind.startsWith("uint")){if(!nil&&(!input.isIntegralNumber()||!input.canConvertToLong()))throw new IllegalArgumentException("Expected integer process configuration");return LongNode.valueOf(nil?0:input.longValue());}throw new IllegalStateException("Unknown process configuration kind "+kind);
    }
    private static boolean empty(JsonNode value,JsonNode schema){if(value.isNull())return true;return switch(schema.path("kind").asText()){case "pointer","struct"->false;case "bool"->!value.asBoolean();case "string"->value.asText().isEmpty();case "slice","map","array"->value.isEmpty();default->value.isNumber()&&value.asLong()==0;};}
    private static boolean nonemptyArray(JsonNode value){return value.isArray()&&!value.isEmpty();}
    private static void mergeNonzero(ObjectNode target,JsonNode override,List<String> keys){for(String key:keys){JsonNode value=override.path(key);if(value.isNumber()&&value.asLong()!=0||value.isTextual()&&!value.asText().isEmpty())target.set(key,value.deepCopy());}}
    private static void mergeArrays(ObjectNode target,JsonNode override,List<String> keys){for(String key:keys)if(nonemptyArray(override.path(key)))target.set(key,override.get(key).deepCopy());}
    private static void inheritString(ObjectNode target,ObjectNode base,String key){if(target.path(key).asText().isEmpty()&&!base.path(key).asText().isEmpty())target.set(key,base.get(key).deepCopy());}
    private ObjectNode defaultImageActions(){ObjectNode result=json.createObjectNode(),ocr=json.createObjectNode();ArrayNode conditions=json.createArrayNode();conditions.add(json.createObjectNode().put("prop","contain.text").put("is","block"));conditions.add(json.createObjectNode().put("prop","contain.data_visual").put("is","true"));ocr.set("on",conditions);ocr.put("on_unobserved",true);result.set("ocr",ocr);return result;}
}
