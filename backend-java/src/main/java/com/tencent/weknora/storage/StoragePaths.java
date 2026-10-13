package com.tencent.weknora.storage;
import java.util.*;

/** Canonical scope path grammar from types/storage_backend.go and resource.go. */
public final class StoragePaths {
    private StoragePaths(){}
    public record Scope(String backendId,String path){}
    public static Optional<Scope> scope(String value){if(value==null)return Optional.empty();String text=value;if(!text.startsWith("storage://"))return Optional.empty();String rest=text.substring(10);int slash=rest.indexOf('/');if(slash<=0||slash==rest.length()-1)return Optional.empty();String id=rest.substring(0,slash),inner=rest.substring(slash+1);return Optional.of(new Scope(id,inner));}
    public static String inner(String path){return scope(path).map(Scope::path).orElse(path);}
    public static String wrap(String backend,String path){return "storage://"+trim(Objects.toString(backend,""))+"/"+path;}
    public static String resourceHandle(String value){if(value==null)return null;String text=trim(value);return text.matches("resource://[A-Za-z0-9_-]{22}")?text.substring(11):null;}
    public static String provider(String path){String inner=inner(path);int split=inner.indexOf("://");if(split<=0)return "";String scheme=inner.substring(0,split);return Set.of("local","minio","cos","tos","s3","oss","ks3","obs","dummy").contains(scheme)?scheme:"";}
    /** unicode.IsSpace used by Go strings.TrimSpace, including NBSP and NEL. */
    public static String trim(String value){if(value==null)return "";int from=0,to=value.length();while(from<to&&space(value.codePointAt(from)))from+=Character.charCount(value.codePointAt(from));while(to>from&&space(value.codePointBefore(to)))to-=Character.charCount(value.codePointBefore(to));return value.substring(from,to);}
    private static boolean space(int c){return c>=9&&c<=13||c==32||c==0x85||c==0xa0||c==0x1680||c>=0x2000&&c<=0x200a||c==0x2028||c==0x2029||c==0x202f||c==0x205f||c==0x3000;}
}
