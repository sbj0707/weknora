package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.ApiException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Input-only Go SSRF policy. This does not authorize or implement outbound model calls. */
@Component
public final class ModelURLPolicy {
    interface Resolver { InetAddress[] lookup(String hostname) throws UnknownHostException; }
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final Environment env; private final Resolver resolver;
    @org.springframework.beans.factory.annotation.Autowired
    public ModelURLPolicy(JdbcTemplate jdbc,ObjectMapper json,Environment env){this(jdbc,json,env,InetAddress::getAllByName);}
    ModelURLPolicy(JdbcTemplate jdbc,ObjectMapper json,Environment env,Resolver resolver){this.jdbc=jdbc;this.json=json;this.env=env;this.resolver=resolver;}
    private String env(String key){return env.getProperty(key,"");}
    private List<String> whitelist(){
        List<String> entries=new ArrayList<>(Arrays.asList(env("SSRF_WHITELIST").split(",")));
        if(jdbc!=null)try{
            var rows=jdbc.queryForList("SELECT value FROM system_settings WHERE key=?","ssrf.whitelist");
            if(!rows.isEmpty()) {JsonNode node=json.readTree(rows.getFirst().get("value").toString());
                if(node.isNull())entries.clear();else if(node.isArray()&&java.util.stream.StreamSupport.stream(node.spliterator(),false).allMatch(v->v.isTextual()||v.isNull())){entries.clear();node.forEach(v->entries.add(v.isNull()?"":v.asText()));}
            }
        }catch(Exception ignored){/* Go settings resolver degrades to environment on read/decode errors. */}
        entries.addAll(Arrays.asList(env("SSRF_WHITELIST_EXTRA").split(",")));return entries.stream().map(CatalogRuntime::trim).filter(s->!s.isEmpty()).toList();
    }
    private boolean whitelistOnly(){String value=CatalogRuntime.trim(env("SSRF_DNS_WHITELIST_ONLY"));return !value.isEmpty()&&!Set.of("0","f","F","false","FALSE","False").contains(value);}
    private static InetAddress literal(String host){
        if(!host.contains(":")&&!host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))return null;
        if(!host.contains(":")) {for(String part:host.split("\\."))if(Integer.parseInt(part)>255||part.length()>1&&part.startsWith("0"))return null;}
        try{return InetAddress.getByName(host);}catch(Exception ex){return null;}
    }
    static boolean inCIDR(byte[] address,String cidr){
        try {String[] parts=cidr.split("/",-1);if(parts.length!=2)return false;InetAddress ip=literal(parts[0]);if(ip==null)return false;byte[] network=ip.getAddress();int bits=Integer.parseInt(parts[1]);if(bits<0||bits>network.length*8||network.length!=address.length)return false;
            for(int i=0;i<network.length;i++){int remaining=Math.max(0,Math.min(8,bits-i*8)),mask=remaining==0?0:(255<<(8-remaining))&255;if((address[i]&mask)!=(network[i]&mask))return false;}return true;
        }catch(Exception ex){return false;}
    }
    private static boolean byName(String host,List<String> entries){String lower=host.toLowerCase(Locale.ROOT);InetAddress ip=literal(host);
        for(String entry:entries){String e=entry.toLowerCase(Locale.ROOT);if(e.contains("/")){if(ip!=null&&inCIDR(ip.getAddress(),e))return true;}else if(e.startsWith("*.")){String suffix=e.substring(1);if(!e.substring(2).contains("*")&&(lower.endsWith(suffix)||lower.equals(suffix.substring(1))))return true;}else if(!e.contains("*")&&lower.equals(e))return true;}return false;
    }
    public void validate(String raw){
        if(raw.isEmpty())return;String normalized=raw.contains("://")?raw:"https://"+raw,host="";
        try{
            URI uri=new URI(normalized);host=uri.getHost();if(host!=null&&host.startsWith("["))host=host.substring(1,host.length()-1);
            if(host==null||host.isEmpty())throw new IllegalArgumentException("URL has no hostname");
            String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);if(!Set.of("http","https").contains(scheme))throw new IllegalArgumentException("invalid scheme: "+scheme+" (only http/https allowed)");
            List<String> entries=whitelist();boolean only=whitelistOnly();if(byName(host,entries))return;
            if(!only&&literal(host)==null&&entries.stream().anyMatch(s->s.contains("/")))try{for(InetAddress ip:resolver.lookup(host))for(String entry:entries)if(inCIDR(ip.getAddress(),entry))return;}catch(UnknownHostException ignored){}
            if(only)throw new WhitelistOnly();
            if(normalized.getBytes(StandardCharsets.UTF_8).length>2048)throw unsafe("URL exceeds maximum length");
            String lower=host.toLowerCase(Locale.ROOT);
            if(Set.of("localhost","127.0.0.1","::1","0.0.0.0","metadata.google.internal","metadata.tencentyun.com","metadata.aws.internal","host.docker.internal","gateway.docker.internal","kubernetes.docker.internal","kubernetes","kubernetes.default","kubernetes.default.svc","kubernetes.default.svc.cluster.local").contains(lower))throw unsafe("hostname "+host+" is restricted");
            for(String suffix:List.of(".local",".localhost",".internal",".corp",".lan",".home",".localdomain",".svc.cluster.local",".pod.cluster.local"))if(lower.endsWith(suffix))throw unsafe("hostname suffix "+suffix+" is restricted");
            if(literal(host)!=null)throw unsafe("direct IP address access is not allowed, use domain name or add to SSRF_WHITELIST");
            if(host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}|[0-9]{8,10}|0[0-7]+\\..*|(?i:0x[0-9a-f]+\\..*|0x[0-9a-f]{6,8})"))throw unsafe("IP-like hostname format is not allowed");
            InetAddress[] ips;try{ips=resolver.lookup(host);}catch(UnknownHostException ex){throw unsafe("DNS resolution failed for hostname "+host+": cannot verify if it resolves to safe IP");}
            for(InetAddress ip:ips){String reason=restricted(ip.getAddress());if(!reason.isEmpty())throw unsafe("hostname "+host+" resolves to restricted IP "+ip.getHostAddress()+": "+reason);}
            if(Set.of(22,23,25,445,3389,5432,3306,6379,27017,9200,2379,2380,8500,4001).contains(uri.getPort()))throw unsafe("port "+uri.getPort()+" is blocked for security reasons");
        }catch(Exception ex){
            String hint=host==null||host.isEmpty()?raw:host,message;
            if(ex instanceof WhitelistOnly)message="Base URL 未通过安全校验：服务端开启了「仅允许白名单出站」（SSRF_DNS_WHITELIST_ONLY），"+hint+" 不在白名单内，因此在解析域名之前就被拒绝。如该地址确实可信，请联系运维把它加入 SSRF_WHITELIST_EXTRA（支持精确域名 / *.example.com 通配 / IP / CIDR），示例：SSRF_WHITELIST_EXTRA="+hint+",*.example.com,10.0.0.0/8";
            else message="Base URL 未通过安全校验："+(ex instanceof URISyntaxException?"invalid URL: invalid URL format":ex.getMessage())+"。如该地址确实可信，请联系运维在服务端环境变量 SSRF_WHITELIST_EXTRA 中加入该主机（支持精确域名 / *.example.com 通配 / IP / CIDR），示例：SSRF_WHITELIST_EXTRA="+hint+",*.example.com,10.0.0.0/8";
            throw new ApiException(400,"BAD_REQUEST",message);
        }
    }
    private static IllegalArgumentException unsafe(String reason){return new IllegalArgumentException("SSRF validation failed: "+reason);}
    private static class WhitelistOnly extends RuntimeException{}
    static String restricted(byte[] ip){
        if(ip.length==4){int a=ip[0]&255,b=ip[1]&255;
            if(a==10||a==172&&b>=16&&b<=31||a==192&&b==168)return "private IP address";
            if(a==127)return "loopback address";if(a==169&&b==254||a==224&&b==0&&ip[2]==0)return "link-local address";if(a>=224&&a<=239)return "multicast address";
            if(Arrays.equals(ip,new byte[4]))return "unspecified address";
            for(String cidr:List.of("100.64.0.0/10","0.0.0.0/8","240.0.0.0/4","198.18.0.0/15","192.0.0.0/24","192.0.2.0/24","198.51.100.0/24","203.0.113.0/24"))if(inCIDR(ip,cidr))return "restricted range "+cidr;return "";
        }
        if(ip.length!=16)return "invalid address";
        if((ip[0]&254)==252)return "private IP address";
        if(Arrays.equals(ip,literal("::1").getAddress()))return "loopback address";
        if((ip[0]&255)==254&&(ip[1]&192)==128||ip[0]==(byte)255&&(ip[1]&15)==2)return "link-local address";
        if(ip[0]==(byte)255)return "multicast address";if(Arrays.equals(ip,new byte[16]))return "unspecified address";
        if(ip[0]==0&&ip[1]==100&&ip[2]==(byte)255&&ip[3]==(byte)155&&ip[4]==0&&ip[5]==1)return "local-use NAT64 translation address";
        if(ip[0]==(byte)254&&(ip[1]&192)==192)return "site-local IPv6 address";
        if(ip[0]==32&&ip[1]==1&&ip[2]==0&&ip[3]==0)return "Teredo tunneling address";
        byte[] embedded=null;String encoding="";
        if(ip[0]==32&&ip[1]==2){embedded=Arrays.copyOfRange(ip,2,6);encoding="6to4 embedded";}
        else if(ip[0]==0&&ip[1]==100&&ip[2]==(byte)255&&ip[3]==(byte)155&&Arrays.equals(Arrays.copyOfRange(ip,4,12),new byte[8])){embedded=Arrays.copyOfRange(ip,12,16);encoding="NAT64 embedded";}
        else if(Arrays.equals(Arrays.copyOf(ip,12),new byte[12])){embedded=Arrays.copyOfRange(ip,12,16);encoding="IPv4-compatible";}
        if(embedded!=null){String reason=restricted(embedded);if(!reason.isEmpty())return encoding+" "+reason;}return "";
    }
}
