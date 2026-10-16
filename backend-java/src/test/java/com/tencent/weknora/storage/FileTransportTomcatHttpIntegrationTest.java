package com.tencent.weknora.storage;

import static org.assertj.core.api.Assertions.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real Servlet header removal differs from MockHttpServletResponse.setHeader(null). */
class FileTransportTomcatHttpIntegrationTest {
  @TempDir Path temp;
  @Test void realConnectionsRetainSafeHeadersAndRemoveGoErrorAnd304Headers()throws Exception {
    Path base=temp.resolve("files"),file=base.resolve("1/kb/source.txt");Files.createDirectories(file.getParent());Files.writeString(file,"0123456789");
    var store=new LocalFileStore(base,"",null,1024);var tomcat=new Tomcat();tomcat.setBaseDir(temp.resolve("tomcat").toString());tomcat.setPort(0);tomcat.getConnector().setProperty("address","127.0.0.1");
    var context=tomcat.addContext("",temp.toString());Tomcat.addServlet(context,"original",new HttpServlet(){
      @Override protected void doGet(HttpServletRequest request,HttpServletResponse response)throws IOException {response.setHeader("X-Retained","original");FileTransport.serve(request,response,store.open("local://1/kb/source.txt"),"source.txt",false);}
    });context.addServletMappingDecoded("/file","original");
    try{
      tomcat.start();URI uri=URI.create("http://127.0.0.1:"+tomcat.getConnector().getLocalPort()+"/file");var client=HttpClient.newHttpClient();
      var normal=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(normal.statusCode()).isEqualTo(200);assertThat(normal.body()).isEqualTo("0123456789");assertThat(normal.headers().firstValue("Cache-Control")).contains("private, no-store");
      var invalid=client.send(HttpRequest.newBuilder(uri).header("Range","bytes=99-").GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(invalid.statusCode()).isEqualTo(416);assertThat(invalid.body()).isEqualTo("invalid range: failed to overlap\n");assertThat(invalid.headers().firstValue("Cache-Control")).isEmpty();assertThat(invalid.headers().firstValue("Content-Range")).contains("bytes */10");assertThat(invalid.headers().firstValue("X-Retained")).contains("original");assertThat(invalid.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
      var unchanged=client.send(HttpRequest.newBuilder(uri).header("If-None-Match","*").GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(unchanged.statusCode()).isEqualTo(304);assertThat(unchanged.body()).isEmpty();assertThat(unchanged.headers().firstValue("Content-Type")).isEmpty();assertThat(unchanged.headers().firstValue("Cache-Control")).contains("private, no-store");assertThat(unchanged.headers().firstValue("X-Retained")).contains("original");
      var partial=client.send(HttpRequest.newBuilder(uri).header("Range","bytes=2-5").GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(partial.statusCode()).isEqualTo(206);assertThat(partial.body()).isEqualTo("2345");assertThat(partial.headers().firstValue("Content-Range")).contains("bytes 2-5/10");
    }finally{try{tomcat.stop();}finally{tomcat.destroy();}}
  }
}
