package com.tencent.weknora.system;
import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.docreader.*;import com.tencent.weknora.security.*;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Repository;
@Repository
public class SystemParserRepository {
    public record Settings(JsonNode config,String cloudAppId){}
    private final JdbcTemplate jdbc;private final ParserEngineSettings decoder;private final GoSecretCodec secrets;
    public SystemParserRepository(JdbcTemplate jdbc,ParserEngineSettings decoder,GoSecretCodec secrets){this.jdbc=jdbc;this.decoder=decoder;this.secrets=secrets;}
    public Settings tenant(long tenant){
        var rows=jdbc.queryForList("SELECT parser_engine_config,credentials FROM tenants WHERE id=? AND deleted_at IS NULL",tenant);if(rows.isEmpty())return new Settings(NullNode.instance,"");
        var row=rows.getFirst();Object config=row.get("parser_engine_config"),credentials=row.get("credentials");
        JsonNode parsed=config instanceof byte[] bytes?decoder.config(bytes):config==null?NullNode.instance:new ObjectMapper().createObjectNode();
        String appId="";if(credentials instanceof byte[] bytes){JsonNode cloud=decoder.credentials(bytes).path("weknoracloud");String candidate=cloud.path("app_id").asText(""),secret=secrets.decryptLenient(cloud.path("app_secret").asText(""));if(!candidate.isEmpty()&&secret!=null&&!secret.isEmpty())appId=candidate;}
        return new Settings(parsed,appId);
    }
}
