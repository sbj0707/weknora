package com.tencent.weknora.security;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.regex.*;

/** Public invitation fields and deployment TTL rules from the Go invitation flow. */
final class TenantInvitationCodec {
    private static final Duration DEFAULT_TTL=Duration.ofDays(7);
    private static final Pattern PART=Pattern.compile("([0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(ns|us|µs|μs|ms|s|m|h)");
    private static final Map<String,BigDecimal> UNITS=Map.of("ns",BigDecimal.ONE,"us",BigDecimal.valueOf(1000),"µs",BigDecimal.valueOf(1000),"μs",BigDecimal.valueOf(1000),"ms",BigDecimal.valueOf(1000000),"s",BigDecimal.valueOf(1000000000),"m",BigDecimal.valueOf(60000000000L),"h",BigDecimal.valueOf(3600000000000L));
    private TenantInvitationCodec(){}
    static Duration ttl(String raw){
        if(raw==null||raw.isEmpty())return DEFAULT_TTL;
        try{
            String text=raw;boolean negative=false;
            if(text.startsWith("+")||text.startsWith("-")){negative=text.charAt(0)=='-';text=text.substring(1);}
            Matcher matcher=PART.matcher(text);int offset=0;BigInteger total=BigInteger.ZERO;
            while(matcher.find()){
                if(matcher.start()!=offset)throw new IllegalArgumentException();
                BigDecimal term=new BigDecimal(matcher.group(1)).multiply(UNITS.get(matcher.group(2)));
                total=total.add(term.toBigInteger());offset=matcher.end();
            }
            if(offset!=text.length()||offset==0)throw new IllegalArgumentException();
            if(negative)total=total.negate();long nanos=total.longValueExact();if(nanos>0)return Duration.ofNanos(nanos);
        }catch(RuntimeException ignored){}
        try{if(raw.matches("[+-]?[0-9]+")){long seconds=Long.parseLong(raw);if(seconds>0)return Duration.ofNanos(seconds*1000000000L);}}catch(NumberFormatException ignored){}
        return DEFAULT_TTL;
    }
    static String inviteUrl(String configured,String environment,String token){
        if(token==null||token.isEmpty())return "";
        String base=AuthPreferences.trimGoSpace(configured==null?"":configured);
        if(base.isEmpty())base=AuthPreferences.trimGoSpace(environment==null?"":environment);
        int end=base.length();while(end>0&&base.charAt(end-1)=='/')end--;
        return base.substring(0,end)+"/register?token="+token;
    }
    static Map<String,Object> project(Map<String,Object> row,Map<String,Map<String,Object>> users,Map<Long,Map<String,Object>> tenants,boolean showLink,String configured,String environment){
        Map<String,Object> out=new LinkedHashMap<>();long tenant=AuthRepository.number(row,"tenant_id");String user=AuthRepository.string(row,"invitee_user_id");
        out.put("id",AuthRepository.number(row,"id"));out.put("tenant_id",tenant);out.put("invitee_user_id",user);
        if(row.get("invited_by")!=null)out.put("invited_by",AuthRepository.string(row,"invited_by"));
        out.put("role",AuthRepository.string(row,"role"));out.put("status",AuthRepository.string(row,"status"));
        optional(out,"message",AuthRepository.string(row,"message"));
        out.put("expires_at",time(row.get("expires_at")));if(row.get("responded_at")!=null)out.put("responded_at",time(row.get("responded_at")));out.put("created_at",time(row.get("created_at")));
        if(user.isEmpty())out.put("is_share_link",true);long count=AuthRepository.number(row,"accepted_count");if(count!=0)out.put("accepted_count",count);
        var invitee=users.get(user);if(invitee!=null){optional(out,"invitee_email",AuthRepository.string(invitee,"email"));optional(out,"invitee_name",AuthRepository.string(invitee,"username"));}
        if(row.get("invited_by")!=null){var inviter=users.get(AuthRepository.string(row,"invited_by"));if(inviter!=null){optional(out,"inviter_email",AuthRepository.string(inviter,"email"));optional(out,"inviter_name",AuthRepository.string(inviter,"username"));}}
        var workspace=tenants.get(tenant);if(workspace!=null)optional(out,"tenant_name",AuthRepository.string(workspace,"name"));
        if(showLink&&AuthRepository.string(row,"status").equals("pending"))optional(out,"invite_url",inviteUrl(configured,environment,AuthRepository.string(row,"token")));
        return out;
    }
    private static String time(Object value){return new DateTimeFormatterBuilder().appendInstant(-1).toFormatter(Locale.ROOT).format(AuthRepository.instant(value));}
    private static void optional(Map<String,Object> out,String key,String value){if(!value.isEmpty())out.put(key,value);}
}
