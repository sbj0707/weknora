package com.tencent.weknora.storage;

import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** Go filetransport/ServeContent semantics for an already authorized, owned reader. */
public final class FileTransport {
  private FileTransport(){}
  private record Range(long start,long length) {
    String contentRange(long size){return "bytes "+start+"-"+(start+length-1)+"/"+size;}
  }
  private static final Set<String> ACTIVE=Set.of(".svg",".svgz",".html",".htm",".xhtml",".xml",".js",".mjs",".css");
  private static final Map<String,String> TYPES=Map.ofEntries(
      Map.entry(".csv","text/csv; charset=utf-8"),Map.entry(".json","application/json"),Map.entry(".pdf","application/pdf"),
      Map.entry(".doc","application/msword"),Map.entry(".docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
      Map.entry(".ppt","application/vnd.ms-powerpoint"),Map.entry(".pptx","application/vnd.openxmlformats-officedocument.presentationml.presentation"),
      Map.entry(".xls","application/vnd.ms-excel"),Map.entry(".xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
      Map.entry(".png","image/png"),Map.entry(".jpg","image/jpeg"),Map.entry(".jpeg","image/jpeg"),Map.entry(".gif","image/gif"),Map.entry(".webp","image/webp"),
      Map.entry(".txt","text/plain; charset=utf-8"),Map.entry(".md","text/markdown; charset=utf-8"),Map.entry(".mp4","video/mp4"),Map.entry(".mp3","audio/mpeg"),
      Map.entry(".wav","audio/wav"),Map.entry(".zip","application/zip"),Map.entry(".tar","application/x-tar"),Map.entry(".gz","application/gzip"));

  public static void serve(HttpServletRequest request,HttpServletResponse response,InputStream reader,String filename,boolean download)throws IOException {
    serve(request,response,reader,filename,download,null,download);
  }
  public static void serve(HttpServletRequest request,HttpServletResponse response,InputStream reader,String filename,boolean download,String contentType,boolean transferHeaders)throws IOException {
    try(reader){
      String ext=filename.substring(Math.max(0,filename.lastIndexOf('.')));if(filename.lastIndexOf('.')<0)ext="";ext=ext.toLowerCase(Locale.ROOT);
      String type=contentType!=null?contentType:download?"application/octet-stream":TYPES.getOrDefault(ext,"application/octet-stream");
      response.setHeader("Content-Type",type);response.setHeader("X-Content-Type-Options","nosniff");
      String disposition=download||ACTIVE.contains(ext)?"attachment":"inline";
      response.setHeader("Content-Disposition",filename.isEmpty()?disposition:disposition(disposition,GoMimeTypes.base(filename)));
      response.setHeader("Cache-Control","private, no-store");
      if(transferHeaders){response.setHeader("Content-Description","File Transfer");response.setHeader("Content-Transfer-Encoding","binary");response.setHeader("Expires","0");}
      if(!(reader instanceof SeekableFileInputStream file)){
        response.setHeader("Accept-Ranges","none");response.setStatus(200);
        if(!request.getMethod().equals("HEAD"))reader.transferTo(response.getOutputStream());return;
      }
      // These handlers never provide an ETag or modification time. Date preconditions cannot match.
      String match=header(request,"If-Match");
      if(!match.isEmpty()&&!hasWildcard(match)){response.setStatus(412);return;}
      if(hasWildcard(header(request,"If-None-Match"))){if(!Set.of("GET","HEAD").contains(request.getMethod())){response.setStatus(412);return;}removeHeaders(response,Set.of("content-type","content-length","content-encoding"));response.setStatus(304);return;}
      String range=header(request,"Range");if(Set.of("GET","HEAD").contains(request.getMethod())&&!header(request,"If-Range").isEmpty())range="";
      long size;try{size=file.size();file.position(0);}catch(IOException failure){error(response,500,"seeker can't seek");return;}
      List<Range> ranges;
      try{ranges=ranges(range,size);}catch(IllegalArgumentException invalid){
        if(invalid.getMessage().equals("invalid range: failed to overlap")&&size==0)ranges=new ArrayList<>();
        else{if(invalid.getMessage().equals("invalid range: failed to overlap"))response.setHeader("Content-Range","bytes */"+size);error(response,416,invalid.getMessage());return;}
      }
      long total=0;for(var r:ranges)total+=r.length();if(total>size)ranges.clear();
      response.setHeader("Accept-Ranges","bytes");
      if(ranges.size()==1){var r=ranges.getFirst();response.setHeader("Content-Range",r.contentRange(size));response.setContentLengthLong(r.length());response.setStatus(206);if(!request.getMethod().equals("HEAD")){file.position(r.start());copy(file,response.getOutputStream(),r.length());}return;}
      if(ranges.size()>1){
        byte[] random=new byte[30];new SecureRandom().nextBytes(random);String boundary=HexFormat.of().formatHex(random);
        var prefixes=new ArrayList<byte[]>();long length=0;
        for(int i=0;i<ranges.size();i++){var r=ranges.get(i);byte[] prefix=((i==0?"":"\r\n")+"--"+boundary+"\r\nContent-Range: "+r.contentRange(size)+"\r\nContent-Type: "+type+"\r\n\r\n").getBytes(StandardCharsets.UTF_8);prefixes.add(prefix);length+=prefix.length+r.length();}
        byte[] end=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);length+=end.length;
        response.setHeader("Content-Type","multipart/byteranges; boundary="+boundary);response.setContentLengthLong(length);response.setStatus(206);
        if(!request.getMethod().equals("HEAD")){var output=response.getOutputStream();for(int i=0;i<ranges.size();i++){output.write(prefixes.get(i));file.position(ranges.get(i).start());copy(file,output,ranges.get(i).length());}output.write(end);}return;
      }
      response.setContentLengthLong(size);response.setStatus(200);if(!request.getMethod().equals("HEAD"))copy(file,response.getOutputStream(),size);
    }
  }
  private static String header(HttpServletRequest r,String key){return Objects.toString(r.getHeader(key),"");}
  private static void copy(InputStream in,OutputStream out,long count)throws IOException {byte[] buffer=new byte[65536];while(count>0){int n=in.read(buffer,0,(int)Math.min(buffer.length,count));if(n<0)return;out.write(buffer,0,n);count-=n;}}
  private static void error(HttpServletResponse response,int status,String message)throws IOException {removeHeaders(response,Set.of("cache-control","content-encoding","etag","last-modified"));response.setHeader("Content-Type","text/plain; charset=utf-8");response.setStatus(status);response.getOutputStream().write((message+"\n").getBytes(StandardCharsets.UTF_8));}
  // Servlet setHeader(name,null) is a no-op in Tomcat. Before writing any body,
  // reset and restore the retained headers so removal works on real connections too.
  private static void removeHeaders(HttpServletResponse response,Set<String> removed){
    var retained=new LinkedHashMap<String,List<String>>();for(String name:response.getHeaderNames())if(!removed.contains(name.toLowerCase(Locale.ROOT)))retained.put(name,new ArrayList<>(response.getHeaders(name)));
    int status=response.getStatus();response.reset();response.setStatus(status);retained.forEach((name,values)->values.forEach(value->response.addHeader(name,value)));
  }
  private static boolean hasWildcard(String raw){String rest=raw;for(;;){rest=trim(rest);if(rest.isEmpty())return false;if(rest.startsWith(",")){rest=rest.substring(1);continue;}if(rest.startsWith("*"))return true;int start=rest.startsWith("W/")?2:0;if(rest.length()<start+2||rest.charAt(start)!='"')return false;int end=start+1;for(;end<rest.length();end++){char c=rest.charAt(end);if(c=='"')break;if(!(c==0x21||c>=0x23&&c<=0x7e||c>=0x80))return false;}if(end==rest.length())return false;rest=rest.substring(end+1);}}
  private static String trim(String s){int a=0,b=s.length();while(a<b&&(s.charAt(a)==' '||s.charAt(a)=='\t'))a++;while(b>a&&(s.charAt(b-1)==' '||s.charAt(b-1)=='\t'))b--;return s.substring(a,b);}
  private static long number(String raw){if(!raw.matches("[+-]?[0-9]+"))throw new NumberFormatException();return Long.parseLong(raw);}
  private static List<Range> ranges(String raw,long size){
    var out=new ArrayList<Range>();if(raw.isEmpty())return out;if(!raw.startsWith("bytes="))throw new IllegalArgumentException("invalid range");boolean noOverlap=false;
    try{for(String piece:raw.substring(6).split(",",-1)){piece=trim(piece);if(piece.isEmpty())continue;int dash=piece.indexOf('-');if(dash<0)throw new NumberFormatException();String first=trim(piece.substring(0,dash)),last=trim(piece.substring(dash+1));
      if(first.isEmpty()){if(last.isEmpty()||last.startsWith("-"))throw new NumberFormatException();long n=number(last);if(n<0)throw new NumberFormatException();n=Math.min(size,n);out.add(new Range(size-n,n));}
      else{long start=number(first);if(start<0)throw new NumberFormatException();if(start>=size){noOverlap=true;continue;}long end=last.isEmpty()?size-1:number(last);if(end<start)throw new NumberFormatException();out.add(new Range(start,Math.min(end,size-1)-start+1));}
    }}catch(NumberFormatException failure){throw new IllegalArgumentException("invalid range");}
    if(noOverlap&&out.isEmpty())throw new IllegalArgumentException("invalid range: failed to overlap");return out;
  }
  /** mime.FormatMediaType's ASCII token/quoted filename or UTF-8 RFC2231 parameter. */
  private static String disposition(String kind,String name){
    byte[] bytes=name.getBytes(StandardCharsets.UTF_8);boolean encoded=false,token=bytes.length>0;
    for(byte raw:bytes){int c=raw&255;if((c<32||c>126)&&c!=9)encoded=true;if(c<=32||c>=127||special(c))token=false;}
    if(encoded){StringBuilder out=new StringBuilder(kind+"; filename*=utf-8''");for(byte raw:bytes){int c=raw&255;if(c<=32||c>=127||c=='*'||c=='\''||c=='%'||special(c))out.append('%').append("0123456789ABCDEF".charAt(c>>4)).append("0123456789ABCDEF".charAt(c&15));else out.append((char)c);}return out.toString();}
    return kind+"; filename="+(token?name:"\""+name.replace("\\","\\\\").replace("\"","\\\"")+"\"");
  }
  private static boolean special(int c){return "()<>@,;:\\\"/[]?=".indexOf(c)>=0;}
}
