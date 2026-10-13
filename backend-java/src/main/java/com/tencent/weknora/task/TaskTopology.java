package com.tencent.weknora.task;

import java.util.*;

/** Production Go queue registry and six independent worker allocations. */
public final class TaskTopology {
    private TaskTopology(){}
    public record Definition(String name,String pool,int weight,int sharedWeight,List<String> taskTypes){public Definition{taskTypes=List.copyOf(taskTypes);}}
    public record Setting(String pool,String key,String environment,int fallback){}
    @FunctionalInterface public interface SettingReader{long read(Setting setting);}
    private static Definition queue(String name,String pool,int weight,int shared,String...types){return new Definition(name,pool,weight,shared,List.of(types));}
    private static final List<Definition> DEFINITIONS=List.of(
        queue("default","core",1,3,"document:process","manual:process"),
        queue("chat_attachment","core",3,3,"temporary_document:process"),
        queue("postprocess","postprocess",1,0,"knowledge:post_process"),
        queue("summary","enrichment",2,2,"summary:generation","datatable:summary","knowledge:auto_tag","kb:profile"),
        queue("multimodal","enrichment",1,1,"image:multimodal"),queue("graph","enrichment",1,1,"chunk:extract"),
        queue("question","enrichment",1,1,"question:generation"),queue("memory","enrichment",1,1,"memory:extract"),
        queue("sync","maintenance",2,0,"datasource:sync"),
        queue("low","maintenance",1,0,"faq:import","kb:clone","index:delete","kb:delete","knowledge:list_delete","knowledge:list_reparse","knowledge:move"),
        queue("wiki","wiki",1,0,"wiki:ingest","wiki:finalize"));
    private static final List<Setting> SETTINGS=List.of(
        new Setting("core","asynq.core_concurrency","WEKNORA_ASYNQ_CORE_CONCURRENCY",8),
        new Setting("postprocess","asynq.postprocess_concurrency","WEKNORA_ASYNQ_POSTPROCESS_CONCURRENCY",2),
        new Setting("enrichment","asynq.enrichment_concurrency","WEKNORA_ASYNQ_ENRICHMENT_CONCURRENCY",12),
        new Setting("maintenance","asynq.maintenance_concurrency","WEKNORA_ASYNQ_MAINTENANCE_CONCURRENCY",4),
        new Setting("shared","asynq.shared_concurrency","WEKNORA_ASYNQ_SHARED_CONCURRENCY",6),
        new Setting("wiki","asynq.wiki_concurrency","WEKNORA_WIKI_ASYNQ_CONCURRENCY",8));
    public static List<Definition> definitions(){return DEFINITIONS;}
    public static List<Setting> settings(){return SETTINGS;}
    public static Map<String,Integer> queues(String pool){Map<String,Integer> weights=new LinkedHashMap<>();for(var definition:DEFINITIONS){int weight=pool.equals("shared")?definition.sharedWeight():pool.equals(definition.pool())?definition.weight():0;if(weight>0)weights.put(definition.name(),weight);}return Collections.unmodifiableMap(weights);}
    public static Optional<String> queueForType(String type){return DEFINITIONS.stream().filter(queue->queue.taskTypes().contains(type)).map(Definition::name).findFirst();}
    public static Map<String,Integer> concurrency(SettingReader reader){Map<String,Integer> allocations=new LinkedHashMap<>();for(var setting:SETTINGS){long value=reader==null?setting.fallback():reader.read(setting);allocations.put(setting.pool(),value<1?setting.fallback():Math.toIntExact(value));}return Collections.unmodifiableMap(allocations);}
}
