package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.ApiException;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;

/** Actual Go private ZIP writer failure, limit and cancellation observations through an overlay. */
class KnowledgeDownloadArchiveGoParityTest {
 private final ObjectMapper json=new ObjectMapper();
 @TestFactory Stream<DynamicTest> archives()throws Exception {
  JsonNode oracle;try(var in=getClass().getResourceAsStream("/go-knowledge-download-archive.json")){oracle=json.readTree(in);}
  assertThat(oracle.path("cases").size()).isEqualTo(20);
  return StreamSupport.stream(oracle.path("cases").spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("fault").asText()+"/"+row.path("limit").asLong(),()->compare(row)));
 }
 private void compare(JsonNode row)throws Exception {
  var buffer=new ByteArrayOutputStream();String fault=row.path("fault").asText();AtomicInteger opened=new AtomicInteger(),closed=new AtomicInteger();OutputStream output=fault.equals("output")?new OutputStream(){public void write(int b)throws IOException{throw new IOException("output failed");}}:buffer;
  var entries=List.of(new KnowledgeDownloadArchive.Entry("a","目录/CON"),new KnowledgeDownloadArchive.Entry("b","目录/CON"));
  boolean large=fault.equals("large-limit");var digest=java.security.MessageDigest.getInstance("SHA-256");if(large){output=new java.security.DigestOutputStream(OutputStream.nullOutputStream(),digest);entries=entries.subList(0,1);}
  ApiException error=null;
  try{
   if(fault.startsWith("context-"))Thread.currentThread().interrupt();
   KnowledgeDownloadArchive.write(output,entries,id->{opened.incrementAndGet();if(fault.equals("open"))throw new IOException("open failed");if(fault.equals("open-app"))throw new ApiException(400,"BAD_REQUEST","injected");if(fault.startsWith("open-")&&(fault.endsWith("canceled")||fault.endsWith("deadline")))throw new InterruptedIOException();
    InputStream stream=large?new InputStream(){long remaining=row.path("source_bytes").asLong();public int read(){if(remaining==0)return -1;remaining--;return 0;}@Override public int read(byte[] b,int off,int len){if(remaining==0)return -1;int n=(int)Math.min(remaining,len);Arrays.fill(b,off,off+n,(byte)0);remaining-=n;return n;}}:new ByteArrayInputStream((id.equals("a")?"abcd":"WXYZ").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new KnowledgeFileService.Content(new FilterInputStream(stream){@Override public int read(byte[] b,int off,int len)throws IOException{if(fault.equals("read"))throw new IOException("read failed");if(fault.startsWith("read-"))throw new InterruptedIOException();return in.read(b,off,len);}@Override public void close()throws IOException{closed.incrementAndGet();if(fault.equals("close"))throw new IOException("close failed");super.close();}},"a.txt");
   },row.path("limit").asLong());
  }catch(ApiException failed){error=failed;}finally{Thread.interrupted();}
  assertThat(opened.get()).isEqualTo(row.path("opened").asInt());assertThat(closed.get()).isEqualTo(row.path("closed").asInt());
  if(row.path("error").isNull()){assertThat(error).isNull();if(large)assertThat(HexFormat.of().formatHex(digest.digest())).as("entire 512 MiB Go ZIP byte digest").isEqualTo(row.path("zip_sha256").asText());else assertThat(buffer.toByteArray()).isEqualTo(Base64.getDecoder().decode(row.path("zip_base64").asText()));}
  else{assertThat(error).isNotNull();assertThat(error.status()).isEqualTo(row.path("error").path("status").asInt());assertThat(error.appCode()).isEqualTo(row.path("error").path("code").asInt());assertThat(error.getMessage()).isEqualTo(row.path("error").path("message").asText());assertThat(json.<JsonNode>valueToTree(error.details())).isEqualTo(row.path("error").path("details"));assertThat(buffer.size()).as("no partial buffered archive flushed on failure").isZero();}
 }
}
