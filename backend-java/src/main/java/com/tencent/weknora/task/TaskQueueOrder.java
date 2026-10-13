package com.tencent.weknora.task;

import java.util.*;

/** Asynq's non-strict weighted shuffle followed by de-duplication, with GCD normalization. */
final class TaskQueueOrder {
    private final List<String> weighted;
    TaskQueueOrder(Map<String,Integer> queues){
        if(queues.isEmpty()||queues.size()>128)throw new IllegalArgumentException("Invalid worker queues");
        int divisor=0,total=0;
        for(var entry:queues.entrySet()){
            if(entry.getKey()==null||entry.getKey().isBlank()||entry.getKey().length()>128||entry.getValue()==null||entry.getValue()<1||entry.getValue()>1000)throw new IllegalArgumentException("Invalid queue weight");
            int value=entry.getValue(),a=divisor,b=value;while(b!=0){int remainder=a%b;a=b;b=remainder;}divisor=a;total+=value;
        }
        if(total/divisor>10000)throw new IllegalArgumentException("Queue weights exceed limit");
        weighted=new ArrayList<>();
        for(var entry:queues.entrySet())for(int i=0;i<entry.getValue()/divisor;i++)weighted.add(entry.getKey());
    }
    List<String> next(){var shuffled=new ArrayList<>(weighted);Collections.shuffle(shuffled);return List.copyOf(new LinkedHashSet<>(shuffled));}
    static Map<String,Integer> parse(String value){
        Map<String,Integer> queues=new LinkedHashMap<>();
        for(String part:value.split(",",-1)){
            String[] pair=part.trim().split("=",-1);
            if(pair.length!=2||queues.putIfAbsent(pair[0].trim(),Integer.parseInt(pair[1].trim()))!=null)throw new IllegalArgumentException("Invalid or duplicate worker queue");
        }
        new TaskQueueOrder(queues);return Map.copyOf(queues);
    }
}
