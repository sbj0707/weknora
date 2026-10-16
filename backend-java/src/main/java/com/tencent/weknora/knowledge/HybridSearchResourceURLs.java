package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.*;
import com.tencent.weknora.storage.StorageResolver;
import java.util.*;
import com.google.re2j.Pattern;
import org.springframework.core.env.Environment;

/** Request-local URL memo prevents duplicate grants; unsupported providers retain their references. */
public final class HybridSearchResourceURLs {
  private static final Pattern REFERENCES=Pattern.compile("\\b(?:resource://[0-9A-Za-z_-]+|(?:storage://[0-9A-Za-z_-]+/)?(?:local|minio|s3|cos|tos|oss|obs|ks3)://[^\\s)\\]>\"]+)");
  private final StorageResolver storage;private final Environment environment;
  public HybridSearchResourceURLs(StorageResolver storage,Environment environment){this.storage=storage;this.environment=environment;}
  public boolean mode(AuthContext caller,String requested){String raw=requested==null?"":requested;boolean explicit=!SummaryGenerationText.trim(raw).isEmpty();if(!explicit)raw=environment.getProperty("RESOURCE_URL_MODE","");String mode=SummaryGenerationText.trim(raw).toLowerCase(Locale.ROOT);if(!Set.of("","handle","public").contains(mode)){if(explicit)throw HybridSearchRequest.bad("invalid resource_urls value "+quote(raw)+": expected \"handle\" or \"public\"",null);mode="handle";}if(mode.equals("public")&&caller.isApiKey()&&!caller.knowledgeBaseIds().isEmpty())throw new ApiException(403,"FORBIDDEN","resource_urls=public is not available for a knowledge-base-restricted API key").withAppCode(1002);return mode.equals("public");}
  public void rewrite(List<ObjectNode> rows,long executionTenant,boolean publicMode){if(rows==null||!publicMode)return;var memo=new HashMap<String,String>();for(var row:rows)for(String field:List.of("content","matched_content","image_info"))if(row.hasNonNull(field)){String text=row.path(field).asText();var matcher=REFERENCES.matcher(text);var rewritten=new StringBuilder();int offset=0;while(matcher.find()){rewritten.append(text,offset,matcher.start()).append(memo.computeIfAbsent(matcher.group(),ref->resolve(executionTenant,ref)));offset=matcher.end();}rewritten.append(text,offset,text.length());row.put(field,rewritten.toString());}}
  private String resolve(long owner,String ref){try{String backend="",provider="";String path=ref;if(ref.startsWith("storage://")){int slash=ref.indexOf('/',10);if(slash<0)return ref;backend=ref.substring(10,slash);path=ref.substring(slash+1);}int colon=path.indexOf("://");if(colon>=0&&!path.startsWith("resource://"))provider=path.substring(0,colon);String value=storage.resolve(owner,backend,provider).files().url(ref);return value.regionMatches(true,0,"http://",0,7)||value.regionMatches(true,0,"https://",0,8)?value:ref;}catch(Exception unavailable){return ref;}}
  private static String quote(String value){try{return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);}catch(Exception impossible){throw new IllegalArgumentException(impossible);}}
}
