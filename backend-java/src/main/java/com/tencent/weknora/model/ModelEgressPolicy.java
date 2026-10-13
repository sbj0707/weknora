package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.model.client.*;
import java.net.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Outbound DNS guard. Kept in model package to share the actual migrated Go ipclass predicates. */
@Component
public final class ModelEgressPolicy implements SafeModelHttp.AddressResolver {
    @FunctionalInterface public interface DNS {InetAddress[] lookup(String hostname)throws UnknownHostException;}
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final Environment environment;private final DNS dns;
    @org.springframework.beans.factory.annotation.Autowired public ModelEgressPolicy(JdbcTemplate jdbc,ObjectMapper json,Environment environment){this(jdbc,json,environment,InetAddress::getAllByName);}
    public ModelEgressPolicy(JdbcTemplate jdbc,ObjectMapper json,Environment environment,DNS dns){this.jdbc=jdbc;this.json=json;this.environment=environment;this.dns=dns;}
    private List<String> entries(){List<String> entries=new ArrayList<>(Arrays.asList(environment.getProperty("SSRF_WHITELIST","").split(",")));if(jdbc!=null)try{var rows=jdbc.queryForList("SELECT value FROM system_settings WHERE key=?","ssrf.whitelist");if(!rows.isEmpty()){JsonNode value=json.readTree(rows.getFirst().get("value").toString());if(value.isNull())entries.clear();else if(value.isArray()&&java.util.stream.StreamSupport.stream(value.spliterator(),false).allMatch(v->v.isTextual()||v.isNull())){entries.clear();value.forEach(v->entries.add(v.isNull()?"":v.asText()));}}}catch(Exception ignored){}
        entries.addAll(Arrays.asList(environment.getProperty("SSRF_WHITELIST_EXTRA","").split(",")));return entries.stream().map(CatalogRuntime::trim).filter(s->!s.isEmpty()).toList();}
    private boolean only(){String raw=CatalogRuntime.trim(environment.getProperty("SSRF_DNS_WHITELIST_ONLY",""));return !raw.isEmpty()&&!Set.of("0","f","F","false","FALSE","False").contains(raw);}
    private static InetAddress literal(String host){if(host.indexOf(':')<0&&!host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))return null;try{if(host.indexOf(':')<0)for(String part:host.split("\\."))if(Integer.parseInt(part)>255||part.length()>1&&part.startsWith("0"))return null;return InetAddress.getByName(host);}catch(Exception ex){return null;}}
    private static boolean name(String host,List<String> entries){String lower=host.toLowerCase(Locale.ROOT);InetAddress literal=literal(host);for(String entry:entries){String e=entry.toLowerCase(Locale.ROOT);if(e.contains("/")){if(literal!=null&&ModelURLPolicy.inCIDR(literal.getAddress(),e))return true;}else if(e.startsWith("*.")&&!e.substring(2).contains("*")){String suffix=e.substring(1);if(lower.endsWith(suffix)||lower.equals(suffix.substring(1)))return true;}else if(!e.contains("*")&&lower.equals(e))return true;}return false;}
    private String env(String upper,String lower){String value=environment.getProperty(upper,"");return value.isEmpty()?environment.getProperty(lower,""):value;}
    private boolean proxyRequired(URI target){
        String proxy=target.getScheme().equals("https")?env("HTTPS_PROXY","https_proxy"):env("HTTP_PROXY","http_proxy");if(proxy.isEmpty())return false;
        String host=target.getHost().toLowerCase(Locale.ROOT);if(host.startsWith("["))host=host.substring(1,host.length()-1);InetAddress ip=literal(host);if(host.equals("localhost")||ip!=null&&ip.isLoopbackAddress())return false;
        int port=target.getPort()<0?(target.getScheme().equals("https")?443:80):target.getPort();
        for(String rule:env("NO_PROXY","no_proxy").toLowerCase(Locale.ROOT).split(",")){
            rule=rule.strip();if(rule.equals("*"))return false;if(rule.isEmpty())continue;
            if(rule.contains("/")){if(ip!=null&&ModelURLPolicy.inCIDR(ip.getAddress(),rule))return false;continue;}
            String ruleHost=rule;int rulePort=-1;
            try{URI parsed=URI.create("http://"+rule);if(parsed.getHost()!=null){ruleHost=parsed.getHost();rulePort=parsed.getPort();if(ruleHost.startsWith("["))ruleHost=ruleHost.substring(1,ruleHost.length()-1);}}catch(IllegalArgumentException ignored){}
            if(rulePort>=0&&rulePort!=port)continue;InetAddress ruleIP=literal(ruleHost);if(ruleIP!=null){if(ip!=null&&ruleIP.equals(ip))return false;continue;}
            boolean subdomainsOnly=ruleHost.startsWith(".")||ruleHost.startsWith("*.");if(ruleHost.startsWith("*."))ruleHost=ruleHost.substring(1);String suffix=ruleHost.startsWith(".")?ruleHost:"."+ruleHost;
            if(host.endsWith(suffix)||!subdomainsOnly&&host.equals(ruleHost))return false;
        }return true;
    }
    @Override public InetAddress[] resolve(URI target,CallContext context){context.check();String host=target.getHost();if(host!=null&&host.startsWith("["))host=host.substring(1,host.length()-1);
        if(host==null||!Set.of("http","https").contains(target.getScheme()))throw new ModelClientException("Model endpoint blocked by SSRF policy");if(proxyRequired(target))throw new ModelClientException("Native model proxy transport is not migrated");List<String> entries=entries();boolean named=name(host,entries),only=only();
        if(only&&!named)throw new ModelClientException("Model host is not in the SSRF whitelist");
        // Run exactly the migrated input policy using the same injected resolver before dialing.
        if(!named)try{new ModelURLPolicy(jdbc,json,environment,dns::lookup).validate(target.toString());}catch(com.tencent.weknora.security.ApiException ex){throw new ModelClientException("Model endpoint blocked by SSRF policy");}
        InetAddress[] addresses;try{addresses=dns.lookup(host);}catch(UnknownHostException ex){throw new ModelClientException("Model DNS resolution failed",true);}context.check();if(addresses.length==0)throw new ModelClientException("Model DNS returned no addresses",true);
        boolean whitelisted=named;
        if(!only&&!whitelisted)for(InetAddress address:addresses)for(String entry:entries)if(ModelURLPolicy.inCIDR(address.getAddress(),entry)){whitelisted=true;break;}
        if(!whitelisted){int port=target.getPort();if(Set.of(22,23,25,445,3389,5432,3306,6379,27017,9200,2379,2380,8500,4001).contains(port))throw new ModelClientException("Model port blocked by SSRF policy");for(InetAddress address:addresses)if(!ModelURLPolicy.restricted(address.getAddress()).isEmpty())throw new ModelClientException("Model DNS answer blocked by SSRF policy");}
        return addresses.clone();
    }
}
