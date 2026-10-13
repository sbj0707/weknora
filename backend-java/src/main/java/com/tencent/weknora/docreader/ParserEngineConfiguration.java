package com.tencent.weknora.docreader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.model.*;import com.tencent.weknora.model.client.SafeModelHttp;import com.tencent.weknora.security.ApiException;
import org.springframework.context.annotation.*;import java.io.IOException;
@Configuration
public class ParserEngineConfiguration {
 @Bean public ParserEngineSettings parserEngineSettings(ObjectMapper json)throws IOException{try(var in=getClass().getResourceAsStream("/parser-settings-schema.json")){return new ParserEngineSettings(json,json.readTree(in));}}
 @Bean(destroyMethod="close") public ParserEngineProbes parserEngineProbes(ModelEgressPolicy egress,ModelURLPolicy policy){return new ParserEngineProbes(new SafeModelHttp(egress,5,true),url->{try{policy.validate(url);}catch(ApiException ex){String reason=ex.getMessage();String prefix="Base URL 未通过安全校验：";if(reason.startsWith(prefix)){reason=reason.substring(prefix.length());int suffix=reason.indexOf("。如该地址确实可信");if(suffix>=0)reason=reason.substring(0,suffix);}throw new IllegalArgumentException(reason);}});}
 @Bean public ParserEngineRegistry parserEngineRegistry(ParserEngineProbes probes){return new ParserEngineRegistry(probes,new ParserEngineRegistry.Availability(false,"anydoc engine not built into this binary (rebuild with `make build-anydoc` / `-tags anydoc`; Docker images need `--build-arg WITH_ANYDOC=1`)"));}
}
