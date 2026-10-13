package com.tencent.weknora.docreader;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Parser rule selection and request overrides shared by knowledge and chat ingestion. */
public final class ParserConfiguration {
    public record Rule(List<String> fileTypes,String engine,Boolean xlsxFirstRowAsHeader) {
        public Rule {
            fileTypes=fileTypes==null?List.of():fileTypes.stream().map(s->s==null?"":s).toList();
            engine=engine==null?"":engine;
        }
    }
    private static final Set<String> STRINGS=Set.of(
        "mineru_endpoint","mineru_api_key","mineru_server_api_key","mineru_tier","mineru_model",
        "mineru_vlm_server_url","mineru_language","mineru_cloud_model","mineru_cloud_language",
        "odl_hybrid","odl_hybrid_url","odl_hybrid_mode","paddleocr_vl_endpoint",
        "paddleocr_vl_cloud_token","paddleocr_vl_cloud_model");
    private static final Set<String> BOOLEANS=Set.of(
        "mineru_enable_formula","mineru_enable_table","mineru_enable_ocr",
        "mineru_cloud_enable_formula","mineru_cloud_enable_table","mineru_cloud_enable_ocr",
        "odl_hybrid_fallback","odl_markdown_with_html","paddleocr_vl_use_seal_recognition",
        "paddleocr_vl_use_chart_recognition","paddleocr_vl_cloud_use_seal_recognition",
        "paddleocr_vl_cloud_use_chart_recognition");
    private final Function<String,String> preferredEngine;
    public ParserConfiguration() {this(ft->"");}
    /** Allows a linked native office converter to supply Go's build-time preference. */
    public ParserConfiguration(Function<String,String> preferredEngine) {this.preferredEngine=preferredEngine;}

    public static String normalizeFileType(String fileType) {
        String normalized=lower(trim(fileType==null?"":fileType));
        return normalized.startsWith(".")?normalized.substring(1):normalized;
    }
    public String defaultEngine(String fileType) {
        String normalized=normalizeFileType(fileType),preferred=preferredEngine.apply(normalized);
        if (preferred!=null&&!preferred.isEmpty()) return preferred;
        return normalized.equals("ppt")||normalized.equals("pptx")?"markitdown":"";
    }
    public Rule resolveKnowledgeRule(List<Rule> rules,String fileType) {
        String normalized=normalizeFileType(fileType);
        return normalized.isEmpty()?null:match(rules,normalized);
    }
    public String resolveKnowledgeEngine(List<Rule> rules,String fileType) {
        Rule rule=resolveKnowledgeRule(rules,fileType);
        return rule==null?defaultEngine(fileType):rule.engine();
    }
    public String resolveChatEngine(List<Rule> rules,String fileType) {
        // Unlike knowledge rules, Go chat rules also match an empty file type
        // and trim the selected engine before dispatch.
        Rule rule=match(rules,normalizeFileType(fileType));
        return rule==null?defaultEngine(fileType):trim(rule.engine());
    }
    private static Rule match(List<Rule> rules,String normalized) {
        if (rules!=null) for (Rule rule:rules) for (String candidate:rule.fileTypes())
            if (normalizeFileType(candidate).equals(normalized)) return rule;
        return null;
    }
    /** Decode the already typed JSON representation stored by Go's configuration DTOs. */
    public static List<Rule> rules(JsonNode array) {
        if (array==null||array.isNull()) return List.of();
        if (!array.isArray()) throw new IllegalArgumentException("parser_engine_rules must be an array");
        List<Rule> rules=new ArrayList<>();
        for (JsonNode item:array) {
            if (!item.isObject()&&!item.isNull()) throw new IllegalArgumentException("parser engine rule must be an object");
            List<String> fileTypes=new ArrayList<>();JsonNode types=item.path("file_types");
            if (!types.isMissingNode()&&!types.isNull()) {
                if (!types.isArray()) throw new IllegalArgumentException("file_types must be an array");
                for (JsonNode type:types) fileTypes.add(string(type,"file_types"));
            }
            JsonNode header=item.path("xlsx_first_row_as_header");
            Boolean asHeader=header.isMissingNode()||header.isNull()?null:bool(header,"xlsx_first_row_as_header");
            rules.add(new Rule(fileTypes,string(item.path("engine"),"engine"),asHeader));
        }
        return List.copyOf(rules);
    }
    public static String resolveMinerUParseMethod(String method,Boolean legacyOCR) {
        String normalized=lower(trim(method==null?"":method));
        return Set.of("auto","ocr","txt").contains(normalized)?normalized:Boolean.FALSE.equals(legacyOCR)?"txt":"auto";
    }
    /** A null map preserves the production Go distinction between unset and empty overrides. */
    public static Map<String,String> tenantOverrides(JsonNode config) {
        if (config==null||config.isNull()) return null;
        if (!config.isObject()) throw new IllegalArgumentException("parser config must be an object");
        Map<String,String> values=new LinkedHashMap<>();
        for (String key:STRINGS) {
            String value=string(config.path(key),key);
            if (!value.isEmpty()) values.put(key,value);
        }
        for (String key:BOOLEANS) {
            JsonNode value=config.path(key);
            if (!value.isMissingNode()&&!value.isNull()) values.put(key,Boolean.toString(bool(value,key)));
        }
        String method=string(config.path("mineru_parse_method"),"mineru_parse_method");
        JsonNode legacy=config.path("mineru_enable_ocr");
        Boolean ocr=legacy.isMissingNode()||legacy.isNull()?null:bool(legacy,"mineru_enable_ocr");
        if (!method.isEmpty()||ocr!=null) values.put("mineru_parse_method",resolveMinerUParseMethod(method,ocr));
        return values.isEmpty()?null:values;
    }
    public static Map<String,String> mergeOverrides(Map<String,String> tenant,Map<String,String> upload) {
        Map<String,String> merged=new LinkedHashMap<>();
        if (tenant!=null) merged.putAll(tenant);
        if (upload!=null) merged.putAll(upload);
        return merged;
    }
    public void applyKnowledgeRuleOverrides(Map<String,String> overrides,List<Rule> rules,String fileType) {
        String normalized=normalizeFileType(fileType);
        if (!normalized.equals("xlsx")&&!normalized.equals("xls")) return;
        Rule rule=resolveKnowledgeRule(rules,normalized);
        if (rule==null||rule.xlsxFirstRowAsHeader()==null) return;
        String engine=trim(rule.engine());
        if (!engine.isEmpty()&&!engine.equals("builtin")) return;
        overrides.put("xlsx_first_row_as_header",rule.xlsxFirstRowAsHeader().toString());
    }
    private static String string(JsonNode node,String field) {
        if (node.isNull()||node.isMissingNode()) return "";
        if (!node.isTextual()) throw new IllegalArgumentException(field+" must be a string");
        return node.textValue();
    }
    private static boolean bool(JsonNode node,String field) {
        if (!node.isBoolean()) throw new IllegalArgumentException(field+" must be a boolean");
        return node.booleanValue();
    }
    private static String lower(String value) {
        StringBuilder output=new StringBuilder();
        value.codePoints().forEach(cp->output.appendCodePoint(Character.toLowerCase(cp)));
        return output.toString();
    }
    private static String trim(String value) {
        int start=0,end=value.length();
        while (start<end&&GoUtf8.isSpace(value.codePointAt(start))) start+=Character.charCount(value.codePointAt(start));
        while (end>start&&GoUtf8.isSpace(value.codePointBefore(end))) end-=Character.charCount(value.codePointBefore(end));
        return value.substring(start,end);
    }
}
