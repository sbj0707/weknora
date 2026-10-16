package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.docreader.GoUtf8;
import com.tencent.weknora.storage.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Original file or manual Markdown; ownership was established by the controller's KB grant. */
@Service
public final class KnowledgeFileService {
  public record Content(InputStream stream,String filename){}
  private final JdbcTemplate jdbc;private final StorageResolver storage;private final ObjectMapper json;private final JsonNode metadataSchema;
  public KnowledgeFileService(JdbcTemplate jdbc,StorageResolver storage,ObjectMapper json){
    this.jdbc=jdbc;this.storage=storage;this.json=json;
    ObjectNode schema=json.createObjectNode().put("kind","struct").put("name","ManualKnowledgeMetadata").put("type","types.ManualKnowledgeMetadata");
    ArrayNode fields=schema.putArray("fields");for(String name:List.of("content","format","status","version","updated_at")){
      ObjectNode field=fields.addObject().put("name",name);field.putObject("schema").put("kind",name.equals("version")?"int":"string").put("type",name.equals("version")?"int":"string");
    }metadataSchema=schema;
  }
  public Content open(long owner,String id)throws IOException {
    var rows=jdbc.queryForList("SELECT * FROM knowledges WHERE tenant_id=? AND id=? AND deleted_at IS NULL",owner,id);
    if(rows.isEmpty())throw new IOException("knowledge not found");var row=rows.getFirst();
    if(Objects.toString(row.get("type"),"").equals("manual")){
      Object raw=row.get("metadata");String text=raw instanceof byte[] bytes?GoUtf8.decode(bytes):Objects.toString(raw,"");
      String content=text.isEmpty()?"":new GoUploadJson(json).decode(text,metadataSchema).path("content").asText("");
      return new Content(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),manualFilename(Objects.toString(row.get("title"),"")));
    }
    String path=Objects.toString(row.get("file_path"),"");
    try{return new Content(storage.forStoredFile(owner,Objects.toString(row.get("knowledge_base_id"),""),path).files().open(path),Objects.toString(row.get("file_name"),""));}
    catch(NoSuchFileException missing){throw new IOException("failed to open file: open "+missing.getFile()+": no such file or directory",missing);}
  }
  static String manualFilename(String title){String safe=title.replace("\n","").replace("\r","").replace("\t","").replace('/', '-').replace('\\','-').replace('"','\'');if(StoragePaths.trim(safe).isEmpty())safe="untitled";if(!safe.toLowerCase(Locale.ROOT).endsWith(".md"))safe+=".md";return safe;}
}
