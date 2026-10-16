package com.tencent.weknora.knowledge;

import com.google.re2j.Pattern;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.storage.FileStore;
import java.io.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import javax.imageio.ImageIO;

/** Go manual Markdown/data-URI/remote-image pass, with bounded SSRF-safe downloads. */
public final class ManualImageResolver implements AutoCloseable {
  public record Image(String original,String serving,String mime){}
  public record Resolved(String markdown,List<Image> images){}
  @FunctionalInterface public interface Fetch {Download get(CallContext context,String url);}
  public record Download(int status,String contentType,byte[] bytes){}
  private record Match(int start,int end,int urlStart,int urlEnd,String url){}
  private static final int LIMIT=10*1024*1024,COUNT=30;
  private static final Pattern LINKED=Pattern.compile("\\[!\\[([^\\]]*)\\]\\(([^()\\s]*(?:\\([^)]*\\)[^()\\s]*)*)\\)\\]\\([^()\\s]*(?:\\([^)]*\\)[^()\\s]*)*\\)");
  private static final Pattern DATA=Pattern.compile("!\\[(.*?)\\]\\((?i:(data:image/[^;]+;base64,\\s*[^)]+))\\)");
  private static final Pattern MARKDOWN=Pattern.compile("!\\[(.*?)\\]\\(([^()\\s]*(?:\\([^)]*\\)[^()\\s]*)*)\\)");
  private static final Pattern HTML=Pattern.compile("(?i)<img\\b([^>]*?)\\ssrc\\s*=\\s*['\"]([^'\"]+)['\"]([^>]*)>");
  private final java.util.concurrent.ScheduledExecutorService monitors=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"manual-image-cancel");t.setDaemon(true);return t;});
  private final Fetch fetch;private final Set<String> keep;private final SafeModelHttp http;
  public ManualImageResolver(SafeModelHttp http,String whitelist){this.http=http;this.keep=hosts(whitelist);this.fetch=(call,url)->{try(var response=http.get(call,url,Map.of("User-Agent","Mozilla/5.0 (compatible; WeKnora/1.0)"),Duration.ofSeconds(15))){return new Download(response.status(),response.headers().getOrDefault("content-type",""),response.bytes(LIMIT));}};}
  ManualImageResolver(Fetch fetch,String whitelist){this.fetch=fetch;this.keep=hosts(whitelist);this.http=null;}
  public Resolved resolve(String markdown,FileStore files,long owner,Runnable guard)throws IOException {return resolve(markdown,files,owner,guard,false,()->false);}
  public Resolved resolveForManual(String markdown,FileStore files,long owner,Runnable guard)throws IOException {return resolve(markdown,files,owner,guard,true,()->false);}
  public Resolved resolveForManual(String markdown,FileStore files,long owner,Runnable guard,java.util.function.BooleanSupplier canceled)throws IOException {return resolve(markdown,files,owner,guard,true,canceled);}
  private Resolved resolve(String markdown,FileStore files,long owner,Runnable guard,boolean manual,java.util.function.BooleanSupplier canceled)throws IOException {
    String original=markdown;
    markdown=LINKED.matcher(markdown).replaceAll("![$1]($2)");var images=new ArrayList<Image>();
    var matches=matches(DATA,markdown);int done=0;
    for(int i=matches.size()-1;i>=0;i--){if(done>=COUNT)break;guard.run();var match=matches.get(i);String raw=match.url();int sep=raw.toLowerCase(Locale.ROOT).indexOf(";base64,");if(sep<0)continue;String type=KnowledgeUploadForms.goTrim(raw.substring(0,sep));if(!type.regionMatches(true,0,"data:image/",0,11))continue;String mime="image/"+KnowledgeUploadForms.goTrim(type.substring(11)).toLowerCase(Locale.ROOT);String encoded=KnowledgeUploadForms.goTrim(raw.substring(sep+8)).replace("\n","").replace("\r","").replace("\t","").replace(" ","");if(encoded.isEmpty())continue;
      byte[] bytes;try{bytes=decode(encoded);}catch(IllegalArgumentException error){continue;}if(bytes.length>LIMIT)continue;
      if(icon(bytes)){markdown=markdown.substring(0,match.start())+markdown.substring(match.end());continue;}
      String url;try{guard.run();String ext=extension(mime);url=files.saveBytes(bytes,owner,UUID.randomUUID()+(!ext.isEmpty()?ext:".png"),false);}catch(CancellationException error){throw error;}catch(IOException|RuntimeException error){continue;}
      images.add(new Image(raw,url,mime));markdown=markdown.substring(0,match.urlStart())+url+markdown.substring(match.urlEnd());done++;
    }
    if(manual&&images.isEmpty())markdown=original;
    original=markdown;int before=images.size();markdown=LINKED.matcher(markdown).replaceAll("![$1]($2)");
    markdown=remote(markdown,MARKDOWN,false,files,owner,guard,images,canceled);
    markdown=remote(markdown,HTML,true,files,owner,guard,images,canceled);
    if(manual&&images.size()==before)markdown=original;
    return new Resolved(markdown,List.copyOf(images));
  }
  private String remote(String markdown,Pattern pattern,boolean html,FileStore files,long owner,Runnable guard,List<Image> images,java.util.function.BooleanSupplier canceled){var matches=matches(pattern,markdown);int done=0;for(int i=matches.size()-1;i>=0;i--){var match=matches.get(i);String url=html?htmlUrl(match.url()):match.url();if(!url.startsWith("http://")&&!url.startsWith("https://"))continue;guard.run();if(done>=COUNT)continue;
      try(var call=CallContext.timeout(Duration.ofSeconds(15));var watch=watch(call,canceled)){Download response=fetch.get(call,url);if(response.status()!=200)continue;byte[] bytes=response.bytes();if(bytes.length>LIMIT)continue;String mime=contentType(response.contentType());if(mime.isEmpty())mime="application/octet-stream";if(!mime.startsWith("image/")&&!mime.equals("application/octet-stream"))continue;if(mime.equals("application/octet-stream")){mime=sniff(bytes);if(!mime.startsWith("image/"))continue;}if(icon(bytes))continue;
        String serving=url;if(!keep(url)){String ext=extension(mime);if(ext.isEmpty())ext=urlExtension(url);if(ext.isEmpty())ext=".png";guard.run();serving=files.saveBytes(bytes,owner,UUID.randomUUID()+ext,false);}guard.run();images.add(new Image(url,serving,mime));markdown=markdown.substring(0,match.urlStart())+serving+markdown.substring(match.urlEnd());done++;
      }catch(CancellationException error){throw error;}catch(IOException|RuntimeException ignored){guard.run();}
    }return markdown;}
  private static List<Match> matches(Pattern pattern,String text){var result=new ArrayList<Match>();var matcher=pattern.matcher(text);while(matcher.find())result.add(new Match(matcher.start(),matcher.end(),matcher.start(2),matcher.end(2),matcher.group(2)));return result;}
  private boolean keep(String raw){try{URI uri=URI.create(raw);return keep.contains(Objects.toString(uri.getHost(),"").toLowerCase(Locale.ROOT))||keep.contains(Objects.toString(uri.getRawAuthority(),"").toLowerCase(Locale.ROOT));}catch(IllegalArgumentException error){return false;}}
  private static Set<String> hosts(String raw){var result=new HashSet<String>();for(String host:Objects.toString(raw,"").split(",")){host=KnowledgeUploadForms.goTrim(host).toLowerCase(Locale.ROOT);if(!host.isEmpty())result.add(host);}return Set.copyOf(result);}
  private static byte[] decode(String encoded){try{return Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException error){return Base64.getUrlDecoder().decode(encoded);}}
  private static String htmlUrl(String raw){String url=KnowledgeUploadForms.goTrim(org.jsoup.parser.Parser.unescapeEntities(KnowledgeUploadForms.goTrim(raw),false));int colon=url.indexOf("://");return colon>0?url.substring(0,colon).toLowerCase(Locale.ROOT)+url.substring(colon):url;}
  private static String contentType(String raw){
    int semi=raw.indexOf(';');String type=KnowledgeUploadForms.goTrim(semi<0?raw:raw.substring(0,semi)).toLowerCase(Locale.ROOT);
    if(!type.matches("[!#$%&'*+.^_`|~A-Za-z0-9-]+/[!#$%&'*+.^_`|~A-Za-z0-9-]+"))return "";
    if(semi<0)return type;int i=semi;var values=new HashMap<String,String>();
    while(i<raw.length()){
      while(i<raw.length()&&Character.isWhitespace(raw.charAt(i)))i++;if(i>=raw.length())return type;if(raw.charAt(i++)!=';')return type;
      while(i<raw.length()&&Character.isWhitespace(raw.charAt(i)))i++;if(i>=raw.length())return type;
      int start=i;while(i<raw.length()&&mimeToken(raw.charAt(i)))i++;if(start==i)return type;
      String key=raw.substring(start,i).toLowerCase(Locale.ROOT);while(i<raw.length()&&Character.isWhitespace(raw.charAt(i)))i++;if(i>=raw.length()||raw.charAt(i++)!='=')return type;
      while(i<raw.length()&&Character.isWhitespace(raw.charAt(i)))i++;if(i>=raw.length())return type;String value;
      if(raw.charAt(i)=='"'){i++;var quoted=new StringBuilder();boolean closed=false;while(i<raw.length()){char ch=raw.charAt(i++);if(ch=='"'){closed=true;break;}if(ch=='\\'){if(i>=raw.length())return type;ch=raw.charAt(i++);}if(ch=='\r'||ch=='\n')return type;quoted.append(ch);}if(!closed)return type;value=quoted.toString();}
      else {start=i;while(i<raw.length()&&mimeToken(raw.charAt(i)))i++;if(start==i)return type;value=raw.substring(start,i);}
      String old=values.put(key,value);if(old!=null&&!old.equals(value))return "";
    }return type;
  }
  private static boolean mimeToken(char ch){return ch>32&&ch<127&&"()<>@,;:\\\"/[]?=".indexOf(ch)<0;}
  private static String extension(String mime){return switch(mime){case "image/png"->".png";case "image/jpeg"->".jpg";case "image/gif"->".gif";case "image/webp"->".webp";case "image/bmp"->".bmp";case "image/svg+xml"->".svg";default->"";};}
  private static String urlExtension(String raw){int slash=raw.lastIndexOf('/'),dot=raw.lastIndexOf('.');String ext=dot>slash?raw.substring(dot).toLowerCase(Locale.ROOT):"";return Set.of(".jpg",".jpeg",".png",".gif",".webp",".bmp",".svg").contains(ext)?ext:"";}
  private static String sniff(byte[] b){if(b.length>=8&&b[0]==(byte)0x89&&b[1]=='P'&&b[2]=='N'&&b[3]=='G'&&b[4]==13&&b[5]==10&&b[6]==26&&b[7]==10)return "image/png";if(b.length>=3&&b[0]==(byte)0xff&&b[1]==(byte)0xd8&&b[2]==(byte)0xff)return "image/jpeg";if(b.length>=6&&(new String(b,0,6,java.nio.charset.StandardCharsets.US_ASCII).equals("GIF87a")||new String(b,0,6,java.nio.charset.StandardCharsets.US_ASCII).equals("GIF89a")))return "image/gif";if(b.length>=2&&b[0]=='B'&&b[1]=='M')return "image/bmp";if(b.length>=4&&b[0]==0&&b[1]==0&&(b[2]==1||b[2]==2)&&b[3]==0)return "image/x-icon";if(b.length>=14&&b[12]=='V'&&b[13]=='P'&&new String(b,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")&&new String(b,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"))return "image/webp";return "application/octet-stream";}
  static boolean icon(byte[] bytes){String format=sniff(bytes);if(Set.of("image/png","image/jpeg","image/gif").contains(format))try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){var readers=ImageIO.getImageReaders(input);while(readers.hasNext()){var reader=readers.next();try{reader.setInput(input);return reader.getWidth(0)<64&&reader.getHeight(0)<64;}finally{reader.dispose();}}}catch(IOException|RuntimeException ignored){}return bytes.length<512;}
  private AutoCloseableWatch watch(CallContext call,java.util.function.BooleanSupplier canceled){return new AutoCloseableWatch(monitors.scheduleWithFixedDelay(()->{if(canceled.getAsBoolean())call.cancel();},25,25,java.util.concurrent.TimeUnit.MILLISECONDS));}
  private record AutoCloseableWatch(java.util.concurrent.ScheduledFuture<?> future)implements AutoCloseable{public void close(){future.cancel(false);}}
  @Override public void close(){monitors.shutdownNow();if(http!=null)http.close();}
}
