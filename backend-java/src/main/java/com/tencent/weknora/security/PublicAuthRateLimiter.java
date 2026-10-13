package com.tencent.weknora.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Process-local shared 30-request sliding minute for the two public invite endpoints. */
@Component
final class PublicAuthRateLimiter {
    private final ConcurrentHashMap<String,ArrayDeque<Long>> buckets=new ConcurrentHashMap<>();
    private final LongSupplier now;private final List<Network> trusted;
    private long cleaned;
    @Autowired PublicAuthRateLimiter(Environment environment){this(environment.getProperty("WEKNORA_TRUSTED_PROXIES"),System::nanoTime);}
    PublicAuthRateLimiter(String proxies,LongSupplier now){this.now=now;this.cleaned=now.getAsLong();List<Network> parsed=new ArrayList<>();String raw=proxies==null?"127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fc00::/7":proxies;
        for(String item:raw.split(",")){item=AuthPreferences.trimGoSpace(item);if(item.isEmpty())continue;String[] pieces=item.split("/",-1);byte[] address=ip(pieces[0]);if(address==null||pieces.length>2)throw new IllegalArgumentException("Invalid trusted proxy: "+item);int bits=address.length*8;if(pieces[0].indexOf(':')>=0&&address.length==4)address=mapped(address);try{if(pieces.length==2)bits=Integer.parseInt(pieces[1]);}catch(NumberFormatException ex){throw new IllegalArgumentException("Invalid trusted proxy: "+item);}if(bits<0||bits>address.length*8)throw new IllegalArgumentException("Invalid trusted proxy: "+item);
            for(int bit=bits;bit<address.length*8;bit++)address[bit/8]&=(byte)~(128>>bit%8);
            if(address.length==16&&mappedAddress(address)){address=Arrays.copyOfRange(address,12,16);bits=Math.max(0,bits-96);}parsed.add(new Network(address,bits));}trusted=List.copyOf(parsed);
    }
    boolean allow(String address){long instant=now.getAsLong();cleanup(instant);String key=address==null||address.isEmpty()?"_unknown_":address;var bucket=buckets.computeIfAbsent(key,k->new ArrayDeque<>());synchronized(bucket){while(!bucket.isEmpty()&&instant-bucket.getFirst()>=60_000_000_000L)bucket.removeFirst();if(bucket.size()>=30)return false;bucket.addLast(instant);return true;}}
    private synchronized void cleanup(long instant){if(instant-cleaned<120_000_000_000L)return;cleaned=instant;buckets.forEach((key,bucket)->{synchronized(bucket){if(bucket.isEmpty()||instant-bucket.getLast()>60_000_000_000L)buckets.remove(key,bucket);}});}
    String clientIP(HttpServletRequest request){byte[] remote=ip(AuthPreferences.trimGoSpace(request.getRemoteAddr()==null?"":request.getRemoteAddr()));if(remote==null)return "";
        if(isTrusted(remote)){for(String name:List.of("X-Forwarded-For","X-Real-IP")){List<String> values=Collections.list(request.getHeaders(name));String[] hops=String.join(",",values).split(",",-1);for(int i=hops.length-1;i>=0;i--){String raw=AuthPreferences.trimGoSpace(hops[i]);byte[] address=ip(raw);if(address==null)break;if(i==0||!isTrusted(address))return raw;}}}
        return canonical(remote);
    }
    private boolean isTrusted(byte[] ip){for(Network network:trusted)if(network.contains(ip))return true;return false;}
    private record Network(byte[] ip,int bits){boolean contains(byte[] candidate){if(candidate.length!=ip.length)return false;for(int i=0;i<bits;i++)if((candidate[i/8]&(128>>i%8))!=(ip[i/8]&(128>>i%8)))return false;return true;}}
    private static byte[] ip(String raw){if(raw.isEmpty())return null;if(raw.indexOf(':')>=0){if(!raw.matches("[0-9a-fA-F:.]+"))return null;if(raw.indexOf('.')>=0&&ip(raw.substring(raw.lastIndexOf(':')+1))==null)return null;try{return InetAddress.getByName(raw).getAddress();}catch(Exception ex){return null;}}
        String[] parts=raw.split("\\.",-1);if(parts.length!=4)return null;byte[] out=new byte[4];for(int i=0;i<4;i++){if(!parts[i].matches("0|[1-9][0-9]{0,2}"))return null;int value=Integer.parseInt(parts[i]);if(value>255)return null;out[i]=(byte)value;}return out;
    }
    private static boolean mappedAddress(byte[] address){for(int i=0;i<10;i++)if(address[i]!=0)return false;return address[10]==(byte)255&&address[11]==(byte)255;}
    private static byte[] mapped(byte[] address){byte[] out=new byte[16];out[10]=(byte)255;out[11]=(byte)255;System.arraycopy(address,0,out,12,4);return out;}
    private static String canonical(byte[] bytes){if(bytes.length==4)return (bytes[0]&255)+"."+(bytes[1]&255)+"."+(bytes[2]&255)+"."+(bytes[3]&255);int[] words=new int[8];for(int i=0;i<8;i++)words[i]=(bytes[2*i]&255)*256+(bytes[2*i+1]&255);int start=-1,len=1;for(int i=0;i<8;){if(words[i]!=0){i++;continue;}int j=i;while(j<8&&words[j]==0)j++;if(j-i>len){start=i;len=j-i;}i=j;}StringBuilder out=new StringBuilder();for(int i=0;i<8;i++){if(i==start){out.append("::");i+=len-1;continue;}if(!out.isEmpty()&&out.charAt(out.length()-1)!=':')out.append(':');out.append(Integer.toHexString(words[i]));}return out.toString();}
}
