package com.tencent.weknora.storage;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.math.BigInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Go utils/presign.go HMAC contract. Verification never has an unsigned fallback. */
public final class FileUrlSigner {
    private final byte[] key;private final Clock clock;
    public FileUrlSigner(String signingKey,String aesKey){this(signingKey,aesKey,Clock.systemUTC());}
    public FileUrlSigner(String signingKey,String aesKey,Clock clock){String configured=signingKey==null||signingKey.isEmpty()?Objects.toString(aesKey,""):signingKey;byte[] bytes=configured.getBytes(StandardCharsets.UTF_8);key=bytes.length<16||Set.of("weknora-system-aes-key-32bytes!!","your-32-byte-long-encryption-key!").contains(configured)?null:bytes;this.clock=Objects.requireNonNull(clock);}
    public String sign(String baseUrl,String path,long tenantId,Duration ttl){
        if(key==null)throw new IllegalStateException("presign: SYSTEM_AES_KEY not configured");if(ttl==null||ttl.isZero()||ttl.isNegative())ttl=Duration.ofHours(2);
        long expires=clock.instant().plus(ttl).getEpochSecond();String base=baseUrl.replaceAll("/+$","")+"/api/v1/files/presigned";
        int fragmentIndex=base.indexOf('#'),queryIndex=base.indexOf('?');String originalQuery=queryIndex>=0&&(fragmentIndex<0||queryIndex<fragmentIndex)?base.substring(queryIndex+1,fragmentIndex<0?base.length():fragmentIndex):null;
        String parseable=base;if(originalQuery!=null)parseable=base.substring(0,queryIndex+1)+originalQuery.replaceAll("%(?![a-fA-F0-9]{2})","%25")+(fragmentIndex<0?"":base.substring(fragmentIndex));
        URI parsed=URI.create(parseable.replace(" ","%20"));var values=new TreeMap<String,List<String>>();
        if(originalQuery!=null)for(String pair:originalQuery.split("&")){if(pair.isEmpty()||pair.contains(";"))continue;String[] pieces=pair.split("=",2);try{values.computeIfAbsent(URLDecoder.decode(pieces[0],StandardCharsets.UTF_8),ignored->new ArrayList<>()).add(URLDecoder.decode(pieces.length==1?"":pieces[1],StandardCharsets.UTF_8));}catch(IllegalArgumentException ignored){}}
        values.put("file_path",List.of(path));values.put("tenant_id",List.of(Long.toUnsignedString(tenantId)));values.put("expires",List.of(Long.toString(expires)));values.put("sig",List.of(signature(path,tenantId,expires)));
        String query=values.entrySet().stream().flatMap(entry->entry.getValue().stream().map(value->encode(entry.getKey())+"="+encode(value))).collect(java.util.stream.Collectors.joining("&"));
        String text=parsed.toASCIIString();int fragment=text.indexOf('#');String suffix=fragment<0?"":text.substring(fragment);if(fragment>=0)text=text.substring(0,fragment);int question=text.indexOf('?');if(question>=0)text=text.substring(0,question);return text+"?"+query+suffix;
    }
    public boolean verify(String path,long tenantId,String expires,String signature){if(key==null||expires==null||signature==null)return false;try{if(!expires.matches("[+-]?[0-9]+"))return false;long epoch=Long.parseLong(expires);return clock.instant().getEpochSecond()<=epoch&&java.security.MessageDigest.isEqual(signature(path,tenantId,epoch).getBytes(StandardCharsets.UTF_8),signature.getBytes(StandardCharsets.UTF_8));}catch(Exception ex){return false;}}
    String signature(String path,long tenant,long expires){return HexFormat.of().formatHex(hmac("file_path="+path+"&tenant_id="+Long.toUnsignedString(tenant)+"&expires="+expires));}
    private byte[] hmac(String payload){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));}catch(Exception ex){throw new IllegalStateException("File URL signing unavailable",ex);}}
    public record DerivedGrant(String token,Instant expiresAt){}
    /** Go time.Truncate measures from year 1, not the Unix epoch; arbitrary windows must keep that anchor. */
    public Optional<DerivedGrant> derivedGrant(String resourceId,Duration ttl){
        if(key==null||resourceId==null||resourceId.isEmpty()||ttl.isNegative()||ttl.isZero())return Optional.empty();long window;try{window=ttl.toNanos()/2;}catch(ArithmeticException ex){return Optional.empty();}if(window<=0)return Optional.empty();
        Instant now=clock.instant();BigInteger billion=BigInteger.valueOf(1_000_000_000L),offset=BigInteger.valueOf(62_135_596_800L);BigInteger absolute=BigInteger.valueOf(now.getEpochSecond()).add(offset).multiply(billion).add(BigInteger.valueOf(now.getNano()));BigInteger start=absolute.subtract(absolute.mod(BigInteger.valueOf(window)));BigInteger[] seconds=start.divideAndRemainder(billion);Instant windowStart=Instant.ofEpochSecond(seconds[0].subtract(offset).longValueExact(),seconds[1].longValueExact());
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hmac("resource_grant:v1:"+resourceId+":"+windowStart.getEpochSecond()),16));return Optional.of(new DerivedGrant(token,windowStart.plus(ttl)));
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("*","%2A").replace("%7E","~");}
    public static long tenantFromPath(String value){String inner=StoragePaths.inner(value);if(inner==null)return 0;int scheme=inner.indexOf("://");if(scheme<0)return 0;for(String segment:inner.substring(scheme+3).split("/",-1))try{if(segment.matches("[0-9]+"))return Long.parseUnsignedLong(segment);}catch(NumberFormatException ex){}return 0;}
    public static boolean tenantScope(String path,long tenant){long actual=tenantFromPath(path);return actual!=0&&actual==tenant;}
    public static boolean knowledgeBaseExportScope(String value,long tenant){if(!tenantScope(value,tenant))return false;String inner=StoragePaths.inner(value);int scheme=inner.indexOf("://");if(scheme<0)return false;String[] parts=inner.substring(scheme+3).split("/",-1);for(int i=0;i<parts.length;i++)if(parts[i].equals(Long.toUnsignedString(tenant))&&(i>0&&parts[i-1].equals("exports")||i+1<parts.length&&parts[i+1].equals("exports")))return true;return false;}
}
