package com.tencent.weknora.model.client;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;

/** Go incremental sandbox statistics; emits a bounded ten-line preview, never a whole file. */
final class SandboxFileProgress {
    static final long INTERVAL=120_000_000L;private final String tool;private final LongSupplier clock;private JSONFieldExtractor pathExtractor,contentExtractor;
    private String path="",content="",buffer="",preview="",lastPath="",lastPreview="";private int added,removed,bytes,lastAdded,lastRemoved,lastBytes,lastParseLength;private long lastEmit,lastParse;private boolean emitted;
    SandboxFileProgress(String tool){this(tool,System::nanoTime);}SandboxFileProgress(String tool,LongSupplier clock){this.tool=tool;this.clock=clock;}
    static boolean supported(String tool){return tool.equals("write_sandbox_file")||tool.equals("edit_sandbox_file");}
    ObjectNode feed(String delta){if(delta.isEmpty()||!supported(tool))return null;if(tool.equals("write_sandbox_file"))write(delta);else edit(delta);if(path.isEmpty()&&added==0&&removed==0&&bytes==0)return null;long now=clock.getAsLong();boolean change=!emitted||!path.equals(lastPath)||added!=lastAdded||removed!=lastRemoved;if(!change&&(bytes==lastBytes&&preview.equals(lastPreview)||now-lastEmit<INTERVAL))return null;
        emitted=true;lastPath=path;lastAdded=added;lastRemoved=removed;lastBytes=bytes;lastPreview=preview;lastEmit=now;ObjectNode result=SafeModelHttp.JSON.createObjectNode().put("added_lines",added).put("removed_lines",removed).put("bytes",bytes);if(!path.isEmpty())result.put("path",path);if(!preview.isEmpty())result.put("preview",preview);return result;
    }
    private void path(String delta){if(pathExtractor==null)pathExtractor=new JSONFieldExtractor("path");path+=pathExtractor.feed(delta);}
    private void write(String delta){path(delta);if(contentExtractor==null)contentExtractor=new JSONFieldExtractor("content");content+=contentExtractor.feed(delta);added=lines(content);removed=0;bytes=utf8(content);String[] chunks=content.split("\n",-1);int end=chunks.length;if(end>0&&chunks[end-1].isEmpty())end--;preview=String.join("\n",Arrays.copyOf(chunks,Math.min(10,end)));}
    private void edit(String delta){buffer+=delta;path(delta);long now=clock.getAsLong();int length=utf8(buffer);if(lastParseLength>0&&length-lastParseLength<512&&now-lastParse<INTERVAL)return;lastParse=now;lastParseLength=length;JsonNode obj=partialObject(buffer);if(obj==null)return;if(path.isEmpty()&&obj.path("path").isTextual()&&!obj.path("path").asText().isEmpty())path=obj.path("path").asText();added=removed=0;JsonNode edits=obj.path("edits");if(edits.isArray())for(JsonNode edit:edits)if(edit.isObject()){String old=string(edit,"old_string","oldText"),fresh=string(edit,"new_string","newText");if(!old.isEmpty()||!fresh.isEmpty()){removed+=lines(old);added+=lines(fresh);}}if(added==0&&removed==0){removed=lines(string(obj,"old_string","oldText"));added=lines(string(obj,"new_string","newText"));}bytes=length;preview="";}
    private static int utf8(String text){return text.getBytes(StandardCharsets.UTF_8).length;}
    private static int lines(String text){if(text.isEmpty())return 0;int count=0;for(int i=0;i<text.length();i++)if(text.charAt(i)=='\n')count++;if(!text.endsWith("\n"))count++;return count;}
    private static String string(JsonNode obj,String...keys){for(String key:keys)if(obj.path(key).isTextual())return obj.path(key).asText();return "";}
    private static JsonNode partialObject(String text){try{JsonNode out=SafeModelHttp.JSON.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);if(out!=null&&out.isObject())return out;}catch(Exception ignored){}String closed=close(text);if(closed.equals(text))return null;try{JsonNode out=SafeModelHttp.JSON.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(closed);return out!=null&&out.isObject()?out:null;}catch(Exception ignored){return null;}}
    private static String close(String text){boolean inString=false,escaped=false;ArrayDeque<Character> stack=new ArrayDeque<>();for(int i=0;i<text.length();i++){char c=text.charAt(i);if(escaped){escaped=false;continue;}if(inString){if(c=='\\'){escaped=true;continue;}if(c=='"')inString=false;continue;}switch(c){case '"':inString=true;break;case '{':stack.push('}');break;case '[':stack.push(']');break;case '}':case ']':if(!stack.isEmpty()&&stack.peek()==c)stack.pop();break;}}if(!inString&&!escaped&&stack.isEmpty())return text;StringBuilder out=new StringBuilder(text);if(escaped)out.append('\\');if(inString||escaped)out.append('"');stack.forEach(out::append);return out.toString();}
}
