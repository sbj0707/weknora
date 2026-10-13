package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ModelURLPolicyTest {
    @Test void whitelistOnlyStopsDnsAndAllowsNameWildcardApexAndLiteralCIDR(){
        MockEnvironment env=new MockEnvironment().withProperty("SSRF_DNS_WHITELIST_ONLY","yes").withProperty("SSRF_WHITELIST","*.example.com,10.0.0.0/8,ignored.*.invalid").withProperty("SSRF_WHITELIST_EXTRA","proxy.local");AtomicInteger count=new AtomicInteger();
        ModelURLPolicy policy=new ModelURLPolicy(null,new ObjectMapper(),env,h->{count.incrementAndGet();return new InetAddress[]{InetAddress.getByName("8.8.8.8")};});
        for(String url:java.util.List.of("https://example.com","https://sub.example.com","http://10.1.2.3:22","http://proxy.local"))policy.validate(url);
        assertThatThrownBy(()->policy.validate("https://unlisted.example.net")).hasMessageContaining("在解析域名之前就被拒绝");assertThatThrownBy(()->policy.validate("gopher://example.com")).hasMessageContaining("invalid scheme: gopher");assertThat(count).hasValue(0);
    }
    @Test void dnsAnswersAllMustBePublicAndCidrResolutionHonorsGoWhitelistSemantics(){
        MockEnvironment env=new MockEnvironment();ModelURLPolicy safe=new ModelURLPolicy(null,new ObjectMapper(),env,h->new InetAddress[]{InetAddress.getByName("8.8.8.8")});safe.validate("public.example.net");
        ModelURLPolicy mixed=new ModelURLPolicy(null,new ObjectMapper(),env,h->new InetAddress[]{InetAddress.getByName("8.8.8.8"),InetAddress.getByName("169.254.169.254")});assertThatThrownBy(()->mixed.validate("https://public.example.net")).hasMessageContaining("link-local address");assertThatThrownBy(()->safe.validate("https://public.example.net:22")).hasMessageContaining("port 22 is blocked");
        for(String host:java.util.List.of("host.docker.internal","name.local","127.0.0.1","8.8.8.8","2130706433","0177.0.0.1","0x7f.0x00.0x00.0x01"))assertThatThrownBy(()->safe.validate("https://"+host)).hasMessageContaining("未通过安全校验");
        ModelURLPolicy cidr=new ModelURLPolicy(null,new ObjectMapper(),new MockEnvironment().withProperty("SSRF_WHITELIST","10.0.0.0/8"),h->new InetAddress[]{InetAddress.getByName("10.0.0.1"),InetAddress.getByName("8.8.8.8")});cidr.validate("http://public.example.net:22");
    }
    @Test void translatedIpv6AndReservedRangesRemainRestricted()throws Exception{
        for(String ip:java.util.List.of("0.0.0.0","127.0.0.1","10.1.1.1","172.16.1.1","192.168.1.1","100.64.1.1","198.18.1.1","192.0.0.1","192.0.2.1","198.51.100.1","203.0.113.1","240.0.0.1","169.254.169.254","224.0.0.1","224.0.1.1","::","::1","fc00::1","fe80::1","ff02::1","ff01::1","fec0::1","2001::1","64:ff9b:1::1","2002:a9fe:a9fe::1","64:ff9b::a9fe:a9fe","::a9fe:a9fe"))assertThat(ModelURLPolicy.restricted(InetAddress.getByName(ip).getAddress())).as(ip).isNotEmpty();
        for(String ip:java.util.List.of("8.8.8.8","1.1.1.1","2606:4700:4700::1111","2002:808:808::1","64:ff9b::808:808"))assertThat(ModelURLPolicy.restricted(InetAddress.getByName(ip).getAddress())).as(ip).isEmpty();
    }
}
