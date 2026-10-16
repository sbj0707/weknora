package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.model.ModelClientFactory;
import com.tencent.weknora.security.KnowledgeBaseReadAuthorization;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Native graph stages for the forthcoming session coordinator; no bridge route is displaced here. */
@Configuration
public class GraphQueryConfiguration {
  @Bean GraphQueryScope graphQueryScope(KnowledgeBaseReadAuthorization authorization,JdbcTemplate jdbc){return new GraphQueryScope(authorization,jdbc);}
  @Bean GraphQueryText graphQueryText(ObjectMapper json,Environment environment){return new GraphQueryText(json,template(json,environment));}
  @Bean GraphQueryHydration graphQueryHydration(JdbcTemplate jdbc,ObjectMapper json,GraphQueryScope scope){return new GraphQueryHydration(jdbc,json,scope);}
  @Bean GraphQuerySearch graphQuerySearch(Neo4jGraphRepository graph,GraphQueryScope scope,ModelClientFactory models,GraphQueryText text,GraphQueryHydration hydration){return new GraphQuerySearch(graph,scope,models::chat,text,hydration);}
  static JsonNode template(ObjectMapper json,Environment environment){
    String explicit=environment.getProperty("weknora.go.config.file","");
    Path path=explicit.isEmpty()?List.of(Path.of("config.yaml"),Path.of("config/config.yaml"),Path.of("../config/config.yaml")).stream().filter(Files::isRegularFile).findFirst().orElse(null):Path.of(explicit);
    try {
      if(path!=null){String source=Files.readString(path);var substitutions=Pattern.compile("\\$\\{([^}]+)\\}").matcher(source);source=substitutions.replaceAll(m->{String value=environment.getProperty(m.group(1));return Matcher.quoteReplacement(value==null?m.group():value);});Object root=new Yaml(new SafeConstructor(new LoaderOptions())).load(source);if(root instanceof Map<?,?> map && map.get("extract") instanceof Map<?,?> extract && extract.get("extract_entity") instanceof Map<?,?> structured)return json.valueToTree(structured);}
      try(var input=GraphQueryConfiguration.class.getResourceAsStream("/graph-query-prompt.json")){return json.readTree(Objects.requireNonNull(input));}
    }catch(IOException error){throw new UncheckedIOException("Cannot read graph query template",error);}
  }
}
