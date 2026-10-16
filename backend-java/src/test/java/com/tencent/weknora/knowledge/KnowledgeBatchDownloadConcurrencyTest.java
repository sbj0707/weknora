package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.security.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.*;

class KnowledgeBatchDownloadConcurrencyTest {
 @TempDir Path temp;
 private static final AuthContext AUTH=new AuthContext("actor",1,"contributor",false,null,false,Set.of(),Set.of());
 private MockHttpServletRequest request(){var r=new MockHttpServletRequest("POST","/api/v1/knowledge-bases/kb/knowledge/batch-download");r.setAttribute(CurrentAuth.ATTRIBUTE,AUTH);r.setContent("{\"ids\":[\"doc\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));return r;}
 private Set<Path> archives()throws IOException{try(var list=Files.list(Path.of(System.getProperty("java.io.tmpdir")))){return list.filter(p->p.getFileName().toString().startsWith("weknora-download-")).collect(java.util.stream.Collectors.toSet());}}
 @Test void realGoFourSlotLimitAndFinallyCleanupCoverBusyAndFailedResponses()throws Exception {
  var json=new ObjectMapper();com.fasterxml.jackson.databind.JsonNode oracle;try(var in=getClass().getResourceAsStream("/go-knowledge-download-archive.json")){oracle=json.readTree(in);}assertThat(json.convertValue(oracle.path("slots"),List.class)).containsExactly(true,true,true,true,false);assertThat(oracle.path("reusable").asBoolean()).isTrue();
  var data=new SingleConnectionDataSource("jdbc:sqlite:"+temp.resolve("batch.db"),true);try{
   var jdbc=new JdbcTemplate(data);jdbc.execute("CREATE TABLE knowledges(id TEXT,tenant_id INTEGER,knowledge_base_id TEXT,type TEXT,file_path TEXT,folder_path TEXT,deleted_at TEXT)");jdbc.update("INSERT INTO knowledges VALUES('doc',1,'kb','file','file','',NULL)");
   var writes=mock(KnowledgeBaseWriteAuthorization.class);when(writes.authorizeBatchDownload(any(),anyString(),anyString())).thenReturn(new KnowledgeBaseWriteAuthorization.WriteAccess(json.createObjectNode(),"kb",1,"admin",true));
   var files=mock(KnowledgeFileService.class);var entered=new CountDownLatch(4);var release=new CountDownLatch(1);
   when(files.open(1,"doc")).thenAnswer(call->{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IOException("deadline");return new KnowledgeFileService.Content(new ByteArrayInputStream(new byte[]{1,2,3}),"file.bin");});
   var controller=new KnowledgeBatchDownloadController(jdbc,writes,files,json);Set<Path> before=archives();var pool=Executors.newFixedThreadPool(4);var work=new ArrayList<Future<MockHttpServletResponse>>();
   try{
    for(int i=0;i<4;i++)work.add(pool.submit(()->{var response=new MockHttpServletResponse();controller.download(request(),response,"kb");return response;}));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
    var busy=catchThrowableOfType(()->controller.download(request(),new MockHttpServletResponse(),"kb"),ApiException.class);assertThat(busy.status()).isEqualTo(429);assertThat(busy.appCode()).isEqualTo(1006);verify(files,times(4)).open(1,"doc");
   }finally{release.countDown();pool.shutdown();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
   for(var future:work){var r=future.get();assertThat(r.getStatus()).isEqualTo(200);assertThat(r.getContentAsByteArray()).startsWith((byte)'P',(byte)'K');}assertThat(archives()).isEqualTo(before);
   var failed=new MockHttpServletResponse(){@Override public jakarta.servlet.ServletOutputStream getOutputStream(){throw new IllegalStateException("client disconnected");}};
   assertThatThrownBy(()->controller.download(request(),failed,"kb")).isInstanceOf(IllegalStateException.class);assertThat(archives()).isEqualTo(before);
   for(int i=0;i<4;i++){var r=new MockHttpServletResponse();controller.download(request(),r,"kb");assertThat(r.getStatus()).isEqualTo(200);}assertThat(archives()).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledges",Integer.class)).isEqualTo(1);
  }finally{data.destroy();}
 }
}
