package com.tencent.weknora.knowledge;

import com.google.re2j.Pattern;
import com.tencent.weknora.model.ModelURLPolicy;
import com.tencent.weknora.security.ApiException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Admission checks retain the raw URL for hashing and publication; no network fetch happens here. */
final class KnowledgeURLSecurity {
  private static final List<Pattern> XSS=List.of("<script[^>]*>.*?</script>","<iframe[^>]*>.*?</iframe>",
      "<object[^>]*>.*?</object>","<embed[^>]*>.*?</embed>","<embed[^>]*>","<form[^>]*>.*?</form>",
      "<input[^>]*>","<button[^>]*>.*?</button>","javascript:","vbscript:","onload\\s*=",
      "onerror\\s*=","onclick\\s*=","onmouseover\\s*=","onfocus\\s*=","onblur\\s*=")
      .stream().map(v->Pattern.compile("(?i)"+v)).toList();
  static boolean safeText(String value) {return XSS.stream().noneMatch(p->p.matcher(value).find());}
  static boolean validServiceURL(String url) {
    return (url.startsWith("http://")||url.startsWith("https://"))
        &&url.getBytes(StandardCharsets.UTF_8).length<=2048&&safeText(url);
  }
  static String filename(String raw) {
    try {return RemoteDocumentDownloads.urlFilename(compatible(raw));}catch(Exception error){return "";}
  }
  static void validate(String raw,ModelURLPolicy policy) {
    // net/url diagnoses an invalid host before consulting the SSRF allow-list.
    String normalized=raw.contains("://")?raw:"https://"+raw;
    for(int i=0;i<normalized.length();i++)if(normalized.charAt(i)<32||normalized.charAt(i)==127)
      throw parseFailure(raw,normalized,"net/url: invalid control character in URL");
    int start=normalized.indexOf("://")+3,end=normalized.length();
    for(char delimiter:new char[]{'/','?','#'}){int found=normalized.indexOf(delimiter,start);if(found>=0)end=Math.min(end,found);}
    String authority=normalized.substring(start,end);String host=authority.substring(authority.lastIndexOf('@')+1);
    if(host.indexOf(' ')>=0) {
      throw parseFailure(raw,normalized,"invalid character \" \" in host name");
    }
    int port=host.lastIndexOf(':');
    if(port>=0&&(!host.startsWith("[")||port>host.indexOf(']'))&&!host.substring(port+1).matches("[0-9]*"))
      throw parseFailure(raw,normalized,"invalid port "+quote(host.substring(port))+" after host");
    // net/url leaves RawQuery untouched, but unescapes the path and fragment before SSRF checks.
    int query=normalized.indexOf('?',end),fragment=normalized.indexOf('#',end);
    for(int i=0;i<normalized.length();i++) {
      if(query>=0&&i>query&&(fragment<0||i<fragment))continue;
      if(normalized.charAt(i)=='%') {
        if(i+2>=normalized.length()||Character.digit(normalized.charAt(i+1),16)<0||Character.digit(normalized.charAt(i+2),16)<0)
          throw parseFailure(raw,normalized,"invalid URL escape "+quote(normalized.substring(i,Math.min(i+3,normalized.length()))));
        i+=2;
      }
    }
    try {policy.validate(compatible(raw));}catch(ApiException error) {
      throw ManualKnowledgeRequest.bad(error.getMessage().replaceFirst("^Base URL", "URL"));
    }
  }
  private static ApiException parseFailure(String raw,String normalized,String reason) {
    return ManualKnowledgeRequest.bad("URL 未通过安全校验：invalid URL: parse "+quote(normalized)+": "+reason+"。如该地址确实可信，请联系运维在服务端环境变量 SSRF_WHITELIST_EXTRA 中加入该主机（支持精确域名 / *.example.com 通配 / IP / CIDR），示例：SSRF_WHITELIST_EXTRA="+raw+",*.example.com,10.0.0.0/8");
  }
  private static String compatible(String raw) {
    int scheme=raw.indexOf("://"),start=scheme<0?0:raw.length();
    if(scheme>=0)for(char delimiter:new char[]{'/','?','#'}){int found=raw.indexOf(delimiter,scheme+3);if(found>=0)start=Math.min(start,found);}
    var out=new StringBuilder(raw.substring(0,start));
    for(int i=start;i<raw.length();i++) {
      char c=raw.charAt(i);
      if(c==' '||"<>\\^`{}|\"".indexOf(c)>=0)out.append(String.format("%%%02X",(int)c));
      else out.append(c);
    }
    return out.toString();
  }
  private static String quote(String value) {
    var out=new StringBuilder("\"");value.codePoints().forEach(cp->{switch(cp){
      case 7->out.append("\\a");case 8->out.append("\\b");case 9->out.append("\\t");case 10->out.append("\\n");
      case 11->out.append("\\v");case 12->out.append("\\f");case 13->out.append("\\r");case 34->out.append("\\\"");case 92->out.append("\\\\");
      default->{int type=Character.getType(cp);if(cp<32||cp==127)out.append(String.format("\\x%02x",cp));
        else if(cp!=32&&(type==Character.CONTROL||type==Character.FORMAT||type==Character.PRIVATE_USE||type==Character.SURROGATE||type==Character.UNASSIGNED||type==Character.LINE_SEPARATOR||type==Character.PARAGRAPH_SEPARATOR||type==Character.SPACE_SEPARATOR))out.append(String.format(cp<=65535?"\\u%04x":"\\U%08x",cp));else out.appendCodePoint(cp);}
    }});return out.append('"').toString();
  }
}
