package com.tencent.weknora.chunker;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.tencent.weknora.docreader.GoUtf8;
import java.util.Arrays;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PreviewRequestDecoder {
    record Request(String text,SplitterConfig config,boolean parentChild,int parentSize,int childSize) { }
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(10000).maxNumberLength(Integer.MAX_VALUE)
                    .maxStringLength(Integer.MAX_VALUE).maxNameLength(Integer.MAX_VALUE).build()).build());
    private String text="",strategy="";
    private int size,overlap,tokenLimit,parentSize,childSize;
    private boolean parentChild;
    private List<String> separators=List.of(),languages=List.of();
    private PreviewRequestDecoder() { }
    static Request decode(byte[] bytes) throws IOException {
        // Validate the entire first value before binding. Invalid UTF-8 is accepted
        // inside JSON strings and replaced per byte, as encoding/json does.
        int length=GoJsonFirstValue.length(bytes);
        String valid=GoUtf8.decode(Arrays.copyOf(bytes,length));
        PreviewRequestDecoder request=new PreviewRequestDecoder();
        try (JsonParser parser=JSON.createParser(valid)) {
            JsonToken token=parser.nextToken();
            if (token==JsonToken.VALUE_NULL) return request.result();
            if (token!=JsonToken.START_OBJECT) throw wrong(parser,"Go value of type handler.PreviewChunkingRequest","");
            String field;
            while ((field=parser.nextFieldName())!=null) {
                parser.nextToken();
                switch (fold(field)) {
                    case "text" -> {if (parser.currentToken()!=JsonToken.VALUE_NULL) request.text=string(parser,"PreviewChunkingRequest.text");}
                    case "chunking_config" -> request.config(parser);
                    default -> parser.skipChildren();
                }
            }
        }
        return request.result();
    }
    private static String fold(String field) {return field.toLowerCase(Locale.ROOT).replace("ſ","s");}
    private Request result() {return new Request(text,new SplitterConfig(size,overlap,separators,strategy,tokenLimit,languages),parentChild,parentSize,childSize);}
    private void config(JsonParser parser) throws IOException {
        if (parser.currentToken()==JsonToken.VALUE_NULL) return;
        if (parser.currentToken()!=JsonToken.START_OBJECT) throw wrong(parser,"Go struct field PreviewChunkingRequest.chunking_config of type handler.PreviewChunkingPayload","");
        String field;
        while ((field=parser.nextFieldName())!=null) {
            parser.nextToken();String name=fold(field),path="PreviewChunkingPayload.chunking_config."+name;
            if (parser.currentToken()==JsonToken.VALUE_NULL) {
                if (name.equals("separators")) separators=List.of();
                if (name.equals("languages")) languages=List.of();
                continue;
            }
            switch (name) {
                case "chunk_size" -> size=integer(parser,path);
                case "chunk_overlap" -> overlap=integer(parser,path);
                case "token_limit" -> tokenLimit=integer(parser,path);
                case "parent_chunk_size" -> parentSize=integer(parser,path);
                case "child_chunk_size" -> childSize=integer(parser,path);
                case "strategy" -> strategy=string(parser,path);
                case "separators" -> separators=strings(parser,path);
                case "languages" -> languages=strings(parser,path);
                case "enable_parent_child" -> {
                    if (parser.currentToken()!=JsonToken.VALUE_TRUE && parser.currentToken()!=JsonToken.VALUE_FALSE) throw wrong(parser,"Go struct field "+path+" of type bool","");
                    parentChild=parser.getBooleanValue();
                }
                default -> parser.skipChildren();
            }
        }
    }
    private static String string(JsonParser parser,String path) throws IOException {
        if (parser.currentToken()!=JsonToken.VALUE_STRING) throw wrong(parser,"Go struct field "+path+" of type string","");
        String value=parser.getText();StringBuilder text=new StringBuilder();
        for (int i=0;i<value.length();i++) {
            char cp=value.charAt(i);
            if (Character.isHighSurrogate(cp) && i+1<value.length() && Character.isLowSurrogate(value.charAt(i+1))) text.append(cp).append(value.charAt(++i));
            else if (Character.isSurrogate(cp)) text.append('\uFFFD');
            else text.append(cp);
        }
        return text.toString();
    }
    private static int integer(JsonParser parser,String path) throws IOException {
        String destination="Go struct field "+path+" of type int";
        if (parser.currentToken()!=JsonToken.VALUE_NUMBER_INT) throw wrong(parser,destination,parser.currentToken()==JsonToken.VALUE_NUMBER_FLOAT?" "+parser.getText():"");
        java.math.BigInteger value=parser.getBigIntegerValue();
        if (value.bitLength()>63) throw wrong(parser,destination," "+parser.getText());
        return value.signum()<0?value.max(java.math.BigInteger.valueOf(Integer.MIN_VALUE)).intValue():value.min(java.math.BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
    }
    private static List<String> strings(JsonParser parser,String path) throws IOException {
        if (parser.currentToken()!=JsonToken.START_ARRAY) throw wrong(parser,"Go struct field "+path+" of type []string","");
        List<String> values=new ArrayList<>();
        while (parser.nextToken()!=JsonToken.END_ARRAY) values.add(parser.currentToken()==JsonToken.VALUE_NULL?"":string(parser,path));
        return values;
    }
    private static IOException wrong(JsonParser parser,String destination,String numeric) {
        String type=switch (parser.currentToken()) {
            case START_ARRAY -> "array";case START_OBJECT -> "object";
            case VALUE_TRUE,VALUE_FALSE -> "bool";case VALUE_STRING -> "string";
            default -> "number";
        };
        return new IOException("json: cannot unmarshal "+type+numeric+" into "+destination);
    }
}
