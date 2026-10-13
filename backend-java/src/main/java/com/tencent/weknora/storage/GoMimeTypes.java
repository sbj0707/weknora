package com.tencent.weknora.storage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Go 1.26 MIME extension table and Unix database precedence. No content sniffing. */
final class GoMimeTypes {
    private static final List<Path> GLOBS=paths("/usr/local/share/mime/globs2","/usr/share/mime/globs2");
    private static final List<Path> TYPES=paths("/etc/mime.types","/etc/apache2/mime.types","/etc/apache/mime.types","/etc/httpd/conf/mime.types");
    private static final class Default { static final GoMimeTypes INSTANCE=new GoMimeTypes(GLOBS,TYPES); }
    private final Map<String,String> exact=new HashMap<>(), lower=new HashMap<>();
    record Metadata(String name,String mime,String kind){}
    static GoMimeTypes system(){return Default.INSTANCE;}
    GoMimeTypes(List<Path> globs,List<Path> types){
        put(".ai","application/postscript");
        put(".apk","application/vnd.android.package-archive");
        put(".apng","image/apng");
        put(".avif","image/avif");
        put(".bin","application/octet-stream");
        put(".bmp","image/bmp");
        put(".com","application/octet-stream");
        put(".css","text/css; charset=utf-8");
        put(".csv","text/csv; charset=utf-8");
        put(".doc","application/msword");
        put(".docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        put(".ehtml","text/html; charset=utf-8");
        put(".eml","message/rfc822");
        put(".eps","application/postscript");
        put(".exe","application/octet-stream");
        put(".flac","audio/flac");
        put(".gif","image/gif");
        put(".gz","application/gzip");
        put(".htm","text/html; charset=utf-8");
        put(".html","text/html; charset=utf-8");
        put(".ico","image/vnd.microsoft.icon");
        put(".ics","text/calendar; charset=utf-8");
        put(".jfif","image/jpeg");
        put(".jpeg","image/jpeg");
        put(".jpg","image/jpeg");
        put(".js","text/javascript; charset=utf-8");
        put(".json","application/json");
        put(".m4a","audio/mp4");
        put(".mjs","text/javascript; charset=utf-8");
        put(".mp3","audio/mpeg");
        put(".mp4","video/mp4");
        put(".oga","audio/ogg");
        put(".ogg","audio/ogg");
        put(".ogv","video/ogg");
        put(".opus","audio/ogg");
        put(".pdf","application/pdf");
        put(".pjp","image/jpeg");
        put(".pjpeg","image/jpeg");
        put(".png","image/png");
        put(".ppt","application/vnd.ms-powerpoint");
        put(".pptx","application/vnd.openxmlformats-officedocument.presentationml.presentation");
        put(".ps","application/postscript");
        put(".rdf","application/rdf+xml");
        put(".rtf","application/rtf");
        put(".shtml","text/html; charset=utf-8");
        put(".svg","image/svg+xml");
        put(".text","text/plain; charset=utf-8");
        put(".tif","image/tiff");
        put(".tiff","image/tiff");
        put(".txt","text/plain; charset=utf-8");
        put(".vtt","text/vtt; charset=utf-8");
        put(".wasm","application/wasm");
        put(".wav","audio/wav");
        put(".webm","audio/webm");
        put(".webp","image/webp");
        put(".xbl","text/xml; charset=utf-8");
        put(".xbm","image/x-xbitmap");
        put(".xht","application/xhtml+xml");
        put(".xhtml","application/xhtml+xml");
        put(".xls","application/vnd.ms-excel");
        put(".xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        put(".xml","text/xml; charset=utf-8");
        put(".xsl","text/xml; charset=utf-8");
        put(".zip","application/zip");
        boolean loaded=false;
        for(Path path:globs) {try {loadGlobs(path);loaded=true;break;}catch(NoSuchFileException|AccessDeniedException ignored){}catch(IOException ex){throw new UncheckedIOException("load MIME globs",ex);}}
        if(!loaded)for(Path path:types)try{loadTypes(path);}catch(NoSuchFileException|AccessDeniedException ignored){}catch(IOException ex){throw new UncheckedIOException("load MIME types",ex);}
    }
    private static List<Path> paths(String... values){return Arrays.stream(values).map(Path::of).toList();}
    static String lower(String value){StringBuilder result=new StringBuilder();value.codePoints().forEach(c->result.appendCodePoint(Character.toLowerCase(c)));return result.toString();}
    String extension(String ext){String value=exact.get(ext);return value==null?lower.getOrDefault(lower(ext),""):value;}
    Metadata metadata(String filename){int slash=filename.lastIndexOf('/'),dot=filename.lastIndexOf('.');String mime=extension(dot>slash?lower(filename.substring(dot)):"");String kind=mime.startsWith("image/")?"image":mime.startsWith("audio/")?"audio":mime.startsWith("video/")?"video":"file";return new Metadata(base(filename),mime,kind);}
    static String base(String value){if(value.isEmpty())return ".";int end=value.length();while(end>0&&value.charAt(end-1)=='/')end--;if(end==0)return "/";return value.substring(value.lastIndexOf('/',end-1)+1,end);}
    private void put(String ext,String mime){String normalized=mediaType(mime);if(normalized==null)return;exact.put(ext,normalized);lower.put(lower(ext),normalized);}
    private void loadGlobs(Path file)throws IOException{try(var reader=new BufferedReader(new InputStreamReader(Files.newInputStream(file),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){limit(line);String[] f=line.split(":",-1);if(f.length<3||f[0].isEmpty()||f[0].startsWith("#")||f[2].length()<3||!f[2].startsWith("*."))continue;String ext=f[2].substring(1);if(ext.indexOf('?')>=0||ext.indexOf('*')>=0||ext.indexOf('[')>=0||exact.containsKey(ext))continue;put(ext,f[1]);}}}
    private void loadTypes(Path file)throws IOException{try(var reader=new BufferedReader(new InputStreamReader(Files.newInputStream(file),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){limit(line);String trimmed=StoragePaths.trim(line);if(trimmed.isEmpty())continue;String[] f=trimmed.split("[\\t\\n\\u000b\\f\\r \\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]+");if(f.length<2||f[0].startsWith("#"))continue;for(int i=1;i<f.length&&!f[i].startsWith("#");i++)put("."+f[i],f[0]);}}}
    private static void limit(String line)throws IOException{if(line.getBytes(StandardCharsets.UTF_8).length>=65536)throw new IOException("bufio.Scanner: token too long");}
    private static String mediaType(String mime){return GoMimeMediaType.normalize(mime);}
}
