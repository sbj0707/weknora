package com.tencent.weknora.knowledge;

import com.tencent.weknora.security.ApiException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;

/** Go archive/zip Store headers and streaming data descriptors, with no whole-file buffering. */
public final class KnowledgeDownloadArchive {
  private KnowledgeDownloadArchive(){}
  public record Entry(String id,String folder){}
  @FunctionalInterface public interface Opener {KnowledgeFileService.Content open(String id)throws IOException;}
  private record Central(byte[] name,int flags,long crc,long size,long offset){}
  public static void write(OutputStream target,List<Entry> entries,Opener open,long limit)throws IOException {
    var output=new Counter(new BufferedOutputStream(target,4096));var directory=new ArrayList<Central>();var used=new HashSet<String>();long total=0;
    for(var entry:entries){
      canceled();KnowledgeFileService.Content content;
      try{content=open.open(entry.id());}catch(Exception failed){canceled(failed);throw internal("部分文件无法读取，未生成压缩包，请检查文档后重试").withDetails(Map.of("knowledge_id",entry.id()));}
      byte[] name=uniquePath(entry.folder(),content.filename(),used).getBytes(StandardCharsets.UTF_8);int flags=8;
      for(byte c:name)if((c&255)<32||(c&255)>125||c=='\\'){flags|=0x800;break;}
      long offset=output.count;IOException copyError=null,closeError=null;var crc=new CRC32();long size=0;
      try{
        try{le(output,0x04034b50,4);le(output,20,2);le(output,flags,2);le(output,0,2);le(output,0,4);le(output,0,4);le(output,0,4);le(output,0,4);le(output,name.length,2);le(output,0,2);output.write(name);}
        catch(IOException failed){throw internal("无法写入下载压缩包，请稍后重试");}
        byte[] buffer=new byte[32768];long remaining=limit-total+1;
        try{while(remaining>0){canceled();int n=content.stream().read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0)break;if(n==0)continue;output.write(buffer,0,n);crc.update(buffer,0,n);size+=n;remaining-=n;}}
        catch(IOException failed){copyError=failed;}
      }finally{try{content.stream().close();}catch(IOException failed){closeError=failed;}}
      total+=size;if(total>limit)throw bad("所选文件合计超过 512 MiB，请分批下载");
      canceled(copyError);if(copyError!=null||closeError!=null)throw internal("部分文件读取失败，未生成压缩包，请稍后重试").withDetails(Map.of("knowledge_id",entry.id()));
      try{le(output,0x08074b50,4);le(output,crc.getValue(),4);le(output,size,4);le(output,size,4);}catch(IOException failed){throw internal("无法写入下载压缩包，请稍后重试");}
      directory.add(new Central(name,flags,crc.getValue(),size,offset));
    }
    try{
      long start=output.count;
      for(var c:directory){le(output,0x02014b50,4);le(output,20,2);le(output,20,2);le(output,c.flags(),2);le(output,0,2);le(output,0,4);le(output,c.crc(),4);le(output,c.size(),4);le(output,c.size(),4);le(output,c.name().length,2);le(output,0,2);le(output,0,2);le(output,0,4);le(output,0,4);le(output,c.offset(),4);output.write(c.name());}
      long size=output.count-start;le(output,0x06054b50,4);le(output,0,4);le(output,directory.size(),2);le(output,directory.size(),2);le(output,size,4);le(output,start,4);le(output,0,2);output.flush();
    }catch(IOException failed){throw internal("无法完成下载压缩包，请稍后重试");}
  }
  private static void canceled(){if(Thread.currentThread().isInterrupted())throw bad("下载已取消");}
  private static void canceled(Throwable failure){canceled();for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof InterruptedIOException||cause instanceof java.nio.channels.ClosedByInterruptException||cause instanceof java.util.concurrent.CancellationException)throw bad("下载已取消");}
  private static void le(OutputStream out,long n,int bytes)throws IOException{for(int i=0;i<bytes;i++){out.write((int)n&255);n>>>=8;}}
  private static final class Counter extends FilterOutputStream {long count;Counter(OutputStream out){super(out);}@Override public void write(int n)throws IOException{out.write(n);count++;}@Override public void write(byte[] b,int off,int len)throws IOException{out.write(b,off,len);count+=len;}}
  static ApiException bad(String message){return new ApiException(400,"BAD_REQUEST",message).withAppCode(1000);}
  static ApiException internal(String message){return new ApiException(500,"INTERNAL_SERVER_ERROR",message).withAppCode(1007);}
  static String uniquePath(String folder,String filename,Set<String> used){String base=filename(filename),ext=extension(base),stem=base.substring(0,base.length()-ext.length()),dir=folder(folder);String name=dir.isEmpty()?base:dir+"/"+base;for(int i=2;used.contains(lower(name));i++)name=(dir.isEmpty()?"":dir+"/")+stem+" ("+i+")"+ext;used.add(lower(name));return name;}
  private static String extension(String name){int slash=name.lastIndexOf('/'),dot=name.lastIndexOf('.');return dot>slash?name.substring(dot):"";}
  private static String filename(String value){
    String name=value.replace('\\','/');if(name.isEmpty())name=".";else{int end=name.length();while(end>0&&name.charAt(end-1)=='/')end--;name=end==0?"/":name.substring(name.lastIndexOf('/',end-1)+1,end);}
    name=trim(sanitize(name));if(name.isEmpty())name="document";String ext=extension(name);if(bytes(ext)>20)ext="";String stem=trimRight(shorten(name.substring(0,name.length()-ext.length()),180));if(stem.isEmpty())stem="document";return reserved(stem)+ext;
  }
  private static String folder(String value){var parts=new ArrayList<String>();int total=0;for(String part:value.replace('\\','/').split("/",-1)){part=trim(sanitize(part));if(part.isEmpty()||part.equals(".")||part.equals(".."))continue;part=trimRight(shorten(part,80));if(part.isEmpty())continue;part=reserved(part);if(total+bytes(part)+1>160)break;parts.add(part);total+=bytes(part)+1;}return String.join("/",parts);}
  private static String sanitize(String s){var out=new StringBuilder();s.codePoints().forEach(c->out.appendCodePoint(Character.getType(c)==Character.CONTROL||"<>:\"/\\|?*".indexOf(c)>=0?'_':c));return out.toString();}
  private static String trim(String s){int a=0;while(a<s.length()&&(s.charAt(a)==' '||s.charAt(a)=='.'))a++;return trimRight(s.substring(a));}
  private static String trimRight(String s){int b=s.length();while(b>0&&(s.charAt(b-1)==' '||s.charAt(b-1)=='.'))b--;return s.substring(0,b);}
  private static String shorten(String s,int limit){var out=new StringBuilder();int n=0;for(int cp:s.codePoints().toArray()){int len=cp<=127?1:cp<=2047?2:cp<=65535?3:4;if(n+len>limit)break;out.appendCodePoint(cp);n+=len;}return out.toString();}
  private static int bytes(String s){return s.getBytes(StandardCharsets.UTF_8).length;}
  private static String lower(String s){var out=new StringBuilder();s.codePoints().forEach(c->out.appendCodePoint(Character.toLowerCase(c)));return out.toString();}
  private static String reserved(String s){String first=s.split("\\.",-1)[0];var upper=new StringBuilder();first.codePoints().forEach(c->upper.appendCodePoint(Character.toUpperCase(c)));String value=upper.toString();int[] cp=value.codePoints().toArray();boolean reserved=Set.of("CON","PRN","AUX","NUL").contains(value)||cp.length==4&&(value.startsWith("COM")||value.startsWith("LPT"))&&"123456789¹²³".indexOf(cp[3])>=0;return reserved?"_"+s:s;}
}
