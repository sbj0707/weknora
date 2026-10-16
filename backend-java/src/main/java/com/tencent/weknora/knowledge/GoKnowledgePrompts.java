package com.tencent.weknora.knowledge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Reads Go's selected conversation prompt without loading Go business code. */
@Component
public final class GoKnowledgePrompts {
  private final Environment environment;
  private final Path configuration;
  private final Map<?,?> settings;
  private static final List<String> COLLECTIONS=List.of("system_prompt","context_template","rewrite","fallback","generate_session_title","generate_summary","generate_kb_description","keywords_extraction","agent_system_prompt","graph_extraction","generate_questions","intent_prompts");
  public GoKnowledgePrompts(Environment environment){
    this.environment=environment;
    String explicit=environment.getProperty("weknora.go.config.file","");
    configuration=explicit.isEmpty()?List.of(Path.of("config.yaml"),Path.of("config/config.yaml"),Path.of("../config/config.yaml")).stream().filter(Files::isRegularFile).findFirst().orElse(null):Path.of(explicit);
    settings=configuration==null?Map.of():read(configuration,true);
  }
  public String selected(String key,String override){
    if(override!=null)return override;
    var conversation=settings.get("conversation") instanceof Map<?,?> map?map:Map.of();
    String id=environment.getProperty("CONVERSATION_"+key.toUpperCase(Locale.ROOT),Objects.toString(conversation.get(key),""));
    if(id.isEmpty()||configuration==null)return null;
    Path directory=configuration.toAbsolutePath().getParent().resolve("prompt_templates");
    for(String collection:COLLECTIONS){
      var templates=read(directory.resolve(collection+".yaml"),false).get("templates");
      if(templates instanceof List<?> list)for(Object item:list)if(item instanceof Map<?,?> template&&id.equals(template.get("id")))return Objects.toString(template.get("content"),"");
    }
    return null;
  }
  private Map<?,?> read(Path path,boolean substitute){
    if(!Files.exists(path))return Map.of();
    try{
      String source=Files.readString(path);
      if(substitute){var matcher=Pattern.compile("\\$\\{([^}]+)\\}").matcher(source);source=matcher.replaceAll(match->{String value=environment.getProperty(match.group(1));return Matcher.quoteReplacement(value==null?match.group():value);});}
      Object decoded=new Yaml(new SafeConstructor(new LoaderOptions())).load(source);
      return decoded instanceof Map<?,?> map?map:Map.of();
    }catch(IOException e){throw new UncheckedIOException("Cannot read Go prompt configuration",e);}
  }
}
