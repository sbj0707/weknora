package com.tencent.weknora.security;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Go encoding/json string binding and Gin validator contracts for invitation bodies. */
final class InvitationRequestDecoder {
    enum Schema {
        CREATE("createInvitationRequest","invalid request body","email","role","message"),
        SHARE("createInviteLinkRequest","invalid request body","role","message"),
        LOOKUP("invitationLookupRequest","token is required","token"),
        ACCEPT("acceptInvitationByTokenRequest","token is required","token"),
        REGISTER("registerByInviteRequest","Invalid registration parameters","token","email","username","password");
        final String type,message;final List<String> fields;
        Schema(String type,String message,String...fields){this.type=type;this.message=message;this.fields=List.of(fields);}
    }
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(10000).maxNumberLength(Integer.MAX_VALUE).maxStringLength(Integer.MAX_VALUE).maxNameLength(Integer.MAX_VALUE).build()).build());
    private static final Pattern EMAIL=Pattern.compile("^(?:(?:(?:(?:[a-zA-Z]|\\d|[!#\\$%&'\\*\\+\\-\\/=\\?\\^_`{\\|}~]|[\\x{00A0}-\\x{D7FF}\\x{F900}-\\x{FDCF}\\x{FDF0}-\\x{FFEF}])+(?:\\.([a-zA-Z]|\\d|[!#\\$%&'\\*\\+\\-\\/=\\?\\^_`{\\|}~]|[\\x{00A0}-\\x{D7FF}\\x{F900}-\\x{FDCF}\\x{FDF0}-\\x{FFEF}])+)*)|(?:(?:\\x22)(?:(?:(?:(?:\\x20|\\x09)*(?:\\x0d\\x0a))?(?:\\x20|\\x09)+)?(?:(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]|\\x21|[\\x23-\\x5b]|[\\x5d-\\x7e]|[\\x{00A0}-\\x{D7FF}\\x{F900}-\\x{FDCF}\\x{FDF0}-\\x{FFEF}])|(?:(?:[\\x01-\\x09\\x0b\\x0c\\x0d-\\x7f]|[\\x{00A0}-\\x{D7FF}\\x{F900}-\\x{FDCF}\\x{FDF0}-\\x{FFEF}]))))*(?:(?:(?:\\x20|\\x09)*(?:\\x0d\\x0a))?(\\x20|\\x09)+)?(?:\\x22))))@(?:(?:(?:[a-zA-Z]|\\d|[\\x{00A0}-\\x{D7FF}\\x{F900}-\\x{FDCF}\\x{FDF0}-\\x{FFEF}])|(?:(?:[a-zA-Z]|\\d|[\\x{00A0}-\\x{D7FF}\\x{F900}-\\x{FDCF}\\x{FDF0}-\\x{FFEF}])(?:[a-zA-Z]|\\d|-|\\.|~|[\\x{00A0}-\\x{D7FF}\\x{F900}-\\x{FDCF}\\x{FDF0}-\\x{FFEF}])*(?:[a-zA-Z]|\\d|[\\x{00A0}-\\x{D7FF}\\x{F900}-\\x{FDCF}\\x{FDF0}-\\x{FFEF}])))\\.)+(?:(?:[a-zA-Z]|[\\x{00A0}-\\x{D7FF}\\x{F900}-\\x{FDCF}\\x{FDF0}-\\x{FFEF}])|(?:(?:[a-zA-Z]|[\\x{00A0}-\\x{D7FF}\\x{F900}-\\x{FDCF}\\x{FDF0}-\\x{FFEF}])(?:[a-zA-Z]|\\d|-|\\.|~|[\\x{00A0}-\\x{D7FF}\\x{F900}-\\x{FDCF}\\x{FDF0}-\\x{FFEF}])*(?:[a-zA-Z]|[\\x{00A0}-\\x{D7FF}\\x{F900}-\\x{FDCF}\\x{FDF0}-\\x{FFEF}])))\\.?$");
    private InvitationRequestDecoder(){}
    static Map<String,Object> decode(byte[] raw,Schema schema){
        Map<String,Object> result=new LinkedHashMap<>();schema.fields.forEach(field->result.put(field,""));
        try{
            int size=GoJsonFirstValue.length(raw);
            try(JsonParser p=JSON.createParser(GoUtf8.decode(Arrays.copyOf(raw,size)))){
                JsonToken t=p.nextToken();
                if(t!=JsonToken.VALUE_NULL){
                    if(t!=JsonToken.START_OBJECT)throw new IOException(wrong(t,"Go value of type handler."+schema.type));
                    String field;
                    while((field=p.nextFieldName())!=null){p.nextToken();String name=fold(field);
                        if(!schema.fields.contains(name)){p.skipChildren();continue;}
                        if(p.currentToken()==JsonToken.VALUE_NULL)continue;
                        if(p.currentToken()!=JsonToken.VALUE_STRING)throw new IOException(wrong(p.currentToken(),"Go struct field "+schema.type+"."+name+" of type "+(name.equals("role")?"types.TenantRole":"string")));
                        result.put(name,surrogates(p.getText()));
                    }
                }
            }
        }catch(IOException ex){throw invalid(schema,ex.getMessage());}
        List<String> errors=new ArrayList<>();
        for(String field:schema.fields){if(field.equals("message"))continue;String value=(String)result.get(field),tag=null;
            if(value.isEmpty())tag="required";else if(field.equals("email")&&!email(value))tag="email";else if(field.equals("password")&&value.codePointCount(0,value.length())<6)tag="min";
            if(tag!=null){String label=Character.toUpperCase(field.charAt(0))+field.substring(1);errors.add("Key: '"+schema.type+"."+label+"' Error:Field validation for '"+label+"' failed on the '"+tag+"' tag");}
        }
        if(!errors.isEmpty())throw invalid(schema,String.join("\n",errors));
        return result;
    }
    private static boolean email(String value){
        if(!EMAIL.matcher(value).matches())return false;
        // net/mail.ParseAddress adds dot-atom and quoted-pair checks before the
        // validator regex. The regex already excludes comments/display names.
        int at=value.lastIndexOf('@');String local=value.substring(0,at),domain=value.substring(at+1);
        if(domain.endsWith(".")||domain.contains(".."))return false;
        if(local.startsWith("\"")){
            boolean escaped=false;
            for(int i=1;i<local.length()-1;i++){char ch=local.charAt(i);
                if(!(ch==' '||ch=='\t'||ch>='!'&&ch<='~'||ch>=128))return false;
                if(escaped){escaped=false;continue;}if(ch=='\\')escaped=true;else if(ch=='\"')return false;
            }
            return !escaped;
        }
        return true;
    }
    private static String wrong(JsonToken token,String destination){String type=switch(token){case START_OBJECT->"object";case START_ARRAY->"array";case VALUE_STRING->"string";case VALUE_TRUE,VALUE_FALSE->"bool";case VALUE_NUMBER_INT,VALUE_NUMBER_FLOAT->"number";default->"null";};return "json: cannot unmarshal "+type+" into "+destination;}
    private static ApiException invalid(Schema schema,String details){return new ApiException(400,"INVALID_REQUEST",schema.message).withAppCode(1010).withDetails(details);}
    private static String fold(String value){StringBuilder out=new StringBuilder();value.codePoints().forEach(cp->{out.appendCodePoint(cp==0x17f?'s':Character.toLowerCase(cp));});return out.toString();}
    private static String surrogates(String value){StringBuilder out=new StringBuilder();for(int i=0;i<value.length();i++){char ch=value.charAt(i);if(Character.isHighSurrogate(ch)&&i+1<value.length()&&Character.isLowSurrogate(value.charAt(i+1)))out.append(ch).append(value.charAt(++i));else out.append(Character.isSurrogate(ch)?'\ufffd':ch);}return out.toString();}
}
