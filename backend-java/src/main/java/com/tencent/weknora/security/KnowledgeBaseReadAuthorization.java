package com.tencent.weknora.security;
import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.databind.node.ObjectNode;import com.tencent.weknora.knowledge.KnowledgeBaseCodec;import java.math.BigInteger;import java.util.*;import org.springframework.dao.DataAccessException;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;
/** A read authorization is never an operation-write grant, even if its resolved resource permission is Admin. */
@Service public final class KnowledgeBaseReadAuthorization {
 public record ReadAccess(ObjectNode knowledgeBase,String knowledgeBaseId,long effectiveTenantId,String permission,boolean ownTenant){public boolean permitsWrite(){return false;}}
 private final JdbcTemplate jdbc;private final KnowledgeBaseCodec codec;private final ObjectMapper json;private final Set<String> builtinIds;private final GoAgentConfigDecoder configs;
 @org.springframework.beans.factory.annotation.Autowired public KnowledgeBaseReadAuthorization(JdbcTemplate jdbc,KnowledgeBaseCodec codec,ObjectMapper json,BuiltinAgentRegistry registry){this(jdbc,codec,json,registry.ids());}
 public KnowledgeBaseReadAuthorization(JdbcTemplate jdbc,KnowledgeBaseCodec codec,ObjectMapper json,Set<String> builtinIds){this.builtinIds=Set.copyOf(builtinIds);this.configs=new GoAgentConfigDecoder(json);this.jdbc=jdbc;this.codec=codec;this.json=json;}
 /** KB detail's route capability; callers of other routes must authorize their own route policy separately. */
 public ReadAccess authorizeRead(AuthContext caller,String id,String agentId,String sourceSelector){return authorize(caller,id,agentId,sourceSelector,"retrieve");}
 /** Chat stages retain the original caller and KB allowlist, with the chat route capability. */
 public ReadAccess authorizeChatRead(AuthContext caller,String id,String agentId,String sourceSelector){return authorize(caller,id,agentId,sourceSelector,"chat");}
 public ReadAccess authorizeManagementRead(AuthContext caller,String id,String agentId,String sourceSelector){return authorize(caller,id,agentId,sourceSelector,"manage_kbs",true);}
 private ReadAccess authorize(AuthContext caller,String id,String agentId,String sourceSelector,String capability){return authorize(caller,id,agentId,sourceSelector,capability,false);}
 private ReadAccess authorize(AuthContext caller,String id,String agentId,String sourceSelector,String capability,boolean databaseTypes){
  if(caller.isApiKey()&&!caller.fullAccess()&&!caller.capabilities().contains(capability))throw new ApiException(403,"FORBIDDEN","Forbidden: API key scope does not allow this operation").withResponse(Map.of("error","Forbidden: API key scope does not allow this operation"));
  if(!caller.allowsKnowledgeBase(id))throw forbidden("API key scope does not allow one or more knowledge bases");
  if(caller.tenantId()==0)throw new ApiException(401,"UNAUTHORIZED","Unauthorized").withAppCode(1001);
  Map<String,Object> row;try{var rows=jdbc.queryForList("SELECT * FROM knowledge_bases WHERE id=? AND deleted_at IS NULL",id);if(rows.isEmpty())throw new ApiException(404,"NOT_FOUND","knowledge base not found").withAppCode(1003);row=rows.getFirst();}catch(DataAccessException ex){throw new ApiException(503,"SERVICE_UNAVAILABLE","cannot verify KB access right now").withAppCode(1008);}
  ObjectNode kb;
  try{boolean sqlite=databaseTypes&&Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("SQLite")));kb=databaseTypes?codec.fromDatabaseRow(row,sqlite):codec.fromRow(row);}
  catch(DataAccessException|ApiException failure){if(!databaseTypes&&failure instanceof ApiException original)throw original;throw new ApiException(503,"SERVICE_UNAVAILABLE","cannot verify KB access right now").withAppCode(1008);}
  long owner=AuthRepository.number(row,"tenant_id");boolean own=owner==caller.tenantId();if(own)return new ReadAccess(kb,id,owner,"admin",true);
  String permission=sharedPermission(id,caller);if(level(permission)>=level("viewer"))return new ReadAccess(kb,id,owner,permission,false);
  boolean permitted;if(agentId!=null&&!agentId.isEmpty()){String source=trimGoSpace(sourceSelector==null?"":sourceSelector);BigInteger parsed=BigInteger.ZERO;if(!source.isEmpty()){try{if(!source.matches("[0-9]+"))throw new NumberFormatException();parsed=new BigInteger(source);if(parsed.bitLength()>64)throw new NumberFormatException();}catch(NumberFormatException ex){throw new ApiException(400,"BAD_REQUEST","invalid agent_source_tenant_id").withAppCode(1000);}}permitted=explicitAgent(caller,id,owner,agentId,parsed);}else permitted=anyAgent(caller,id,owner);
  if(!permitted)throw forbidden("Permission denied to access this knowledge base");return new ReadAccess(kb,id,owner,"viewer",false);
 }
 private boolean builtin(String id){return builtinIds.contains(id);}
 private boolean explicitAgent(AuthContext caller,String id,long owner,String agentId,BigInteger source){
  if(builtin(agentId)||source.equals(BigInteger.valueOf(caller.tenantId())))return false;try{String query="SELECT s.source_tenant_id FROM agent_shares s JOIN organizations o ON o.id=s.organization_id AND o.deleted_at IS NULL JOIN organization_tenant_members src ON src.organization_id=s.organization_id AND src.tenant_id=s.source_tenant_id JOIN organization_tenant_members m ON m.organization_id=s.organization_id AND m.tenant_id=? WHERE s.agent_id=? AND s.deleted_at IS NULL";List<Object> args=new ArrayList<>(List.of(caller.tenantId(),agentId));if(source.signum()!=0){query+=" AND s.source_tenant_id=?";if(source.bitLength()>63)return false;args.add(source.longValue());}else{query+=" AND s.source_tenant_id<>?";args.add(caller.tenantId());}query+=" ORDER BY s.id LIMIT 1";var shares=jdbc.queryForList(query,args.toArray());if(shares.isEmpty())return false;var agents=jdbc.queryForList("SELECT * FROM custom_agents WHERE id=? AND tenant_id=? AND deleted_at IS NULL",agentId,shares.getFirst().get("source_tenant_id"));return !agents.isEmpty()&&includes(agents.getFirst(),id,owner);}catch(RuntimeException ex){return false;}
 }
 private boolean anyAgent(AuthContext caller,String id,long owner){try{var agents=jdbc.queryForList("SELECT a.* FROM agent_shares s JOIN organizations o ON o.id=s.organization_id AND o.deleted_at IS NULL JOIN organization_tenant_members src ON src.organization_id=s.organization_id AND src.tenant_id=s.source_tenant_id JOIN organization_tenant_members m ON m.organization_id=s.organization_id AND m.tenant_id=? JOIN custom_agents a ON a.id=s.agent_id AND a.tenant_id=s.source_tenant_id AND a.deleted_at IS NULL WHERE s.deleted_at IS NULL AND s.source_tenant_id<>?",caller.tenantId(),caller.tenantId());for(var agent:agents)configs.decode(AuthRepository.string(agent,"config"));for(var agent:agents)if(includes(agent,id,owner))return true;}catch(RuntimeException ex){}return false;}
 private boolean includes(Map<String,Object> agent,String id,long owner){if(AuthRepository.number(agent,"tenant_id")!=owner||AuthRepository.bool(agent,"is_builtin")||builtin(AuthRepository.string(agent,"id")))return false;try{JsonNode config=configs.decode(AuthRepository.string(agent,"config"));if(config==null)return false;switch(config.path("kb_selection_mode").asText()){case "all":return true;case "selected":for(var value:config.path("knowledge_bases"))if(value.isTextual()&&value.asText().equals(id))return true;return false;default:return false;}}catch(Exception ex){return false;}}
 private static String trimGoSpace(String value){int start=0,end=value.length();while(start<end&&space(value.codePointAt(start)))start+=Character.charCount(value.codePointAt(start));while(start<end&&space(value.codePointBefore(end)))end-=Character.charCount(value.codePointBefore(end));return value.substring(start,end);}
 private static boolean space(int cp){return cp>=9&&cp<=13||cp==32||cp==0x85||cp==0xa0||cp==0x1680||cp>=0x2000&&cp<=0x200a||cp==0x2028||cp==0x2029||cp==0x202f||cp==0x205f||cp==0x3000;}
    private String sharedPermission(String id,AuthContext caller){
        // The production Go share service ignores a failed member lookup and
        // ResolveKB collapses failed share resolution to an access denial.
        String highest="";
        try{var shares=jdbc.queryForList("SELECT s.permission,m.role AS org_role FROM kb_shares s JOIN organizations o ON o.id=s.organization_id AND o.deleted_at IS NULL JOIN organization_tenant_members source_member ON source_member.organization_id=s.organization_id AND source_member.tenant_id=s.source_tenant_id JOIN organization_tenant_members m ON m.organization_id=s.organization_id AND m.tenant_id=? WHERE s.knowledge_base_id=? AND s.deleted_at IS NULL",caller.tenantId(),id);
            for(var share:shares){String granted=AuthRepository.string(share,"permission"),member=AuthRepository.string(share,"org_role");String effective=granted.isEmpty()?member:member.isEmpty()?granted:level(granted)>=level(member)?member:granted;if(caller.role().equals("viewer")&&level(effective)>=level("editor"))effective="viewer";if(highest.isEmpty()||level(effective)>=level(highest))highest=effective;}
        }catch(DataAccessException ignored){}
        return highest;
    }
    private static int level(String role){return switch(role){case "admin"->3;case "editor"->2;case "viewer"->1;default->0;};}

 private static ApiException forbidden(String message){return new ApiException(403,"FORBIDDEN",message).withAppCode(1002);}
}
