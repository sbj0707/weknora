package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.google.re2j.Pattern;
import com.tencent.weknora.storage.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Manual images use the KB owner's existing storage and resource-claim tables. */
public final class ManualProcessingImages {
 private static final Pattern REFERENCES=Pattern.compile("resource://[A-Za-z0-9_-]{22}");
 private final StorageResolver storage;private final ManualImageResolver resolver;private final ObjectMapper json;
 public ManualProcessingImages(StorageResolver storage,ManualImageResolver resolver,ObjectMapper json){this.storage=storage;this.resolver=resolver;this.json=json;}
 ManualImageResolver.Resolved resolve(DocumentProcessingRepository.Snapshot source,String markdown,Runnable guard,java.util.function.BooleanSupplier canceled)throws IOException {
  long owner=source.knowledge().path("tenant_id").asLong();String id=source.knowledge().path("id").asText();guard.run();
  var files=storage.forKnowledgeBase(owner,source.knowledgeBase().path("id").asText()).files();
  var result=resolver.resolveForManual(markdown,files,owner,guard,canceled);guard.run();
  var seen=new HashSet<String>();var matches=REFERENCES.matcher(result.markdown());while(matches.find())if((matches.end()==result.markdown().length()||!handle(result.markdown().charAt(matches.end())))&&seen.add(matches.group()))bind(owner,id,matches.group(),guard);
  for(var image:result.images())bind(owner,id,image.serving(),guard);
  return result;
 }
 private void bind(long owner,String id,String ref,Runnable guard){guard.run();try{storage.catalog().bind(owner,ref,"knowledge",id,"attachment");}catch(CancellationException error){throw error;}catch(RuntimeException unavailable){/* Go binds content/stored images best effort. */}}
 void cleanup(DocumentProcessingRepository.Snapshot source,List<String> raw,Runnable guard)throws IOException {
  var refs=new LinkedHashSet<String>();for(String value:raw)try{var images=json.readTree(value);if(!images.isArray())continue;for(var image:images)if(image.path("url").isTextual()&&!image.path("url").asText().isEmpty())refs.add(image.path("url").asText());}catch(IOException invalid){/* Go ignores invalid image_info arrays. */}
  if(refs.isEmpty())return;long owner=source.knowledge().path("tenant_id").asLong();String id=source.knowledge().path("id").asText();var files=storage.forKnowledgeBase(owner,source.knowledgeBase().path("id").asText()).files();
  for(String ref:refs){guard.run();try{long remaining=storage.catalog().release(owner,ref,"knowledge",id);if(remaining<=0){guard.run();files.delete(ref);}}catch(CancellationException error){throw error;}catch(IOException|RuntimeException retained){/* Go retains on uncertain claims and logs physical delete errors. */}}
 }
 private static boolean handle(char c){return c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||c=='_'||c=='-';}
}
