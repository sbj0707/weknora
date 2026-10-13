package com.tencent.weknora.task;

import java.time.Duration;
import java.util.*;

/** Separate thread pools reserve capacity; the shared pool borrows only from core/enrichment queues. */
public final class TaskPoolGroup implements TaskExecutor {
    private final Map<String,TaskWorker> workers=new LinkedHashMap<>();private final Map<String,Integer> capacities;
    public TaskPoolGroup(TaskRepository tasks,List<TaskHandler> handlers,Map<String,Integer> capacities,Duration lease){
        var names=TaskTopology.settings().stream().map(TaskTopology.Setting::pool).toList();
        if(!capacities.keySet().equals(new HashSet<>(names))||capacities.values().stream().anyMatch(value->value==null||value<1||value>64))throw new IllegalArgumentException("Native task topology requires six pools with capacity 1..64 each");
        this.capacities=Map.copyOf(capacities);
        try{for(String name:names)workers.put(name,new TaskWorker(tasks,handlers,capacities.get(name),lease,TaskTopology.queues(name)));}catch(RuntimeException failure){close();throw failure;}
    }
    @Override public void poll(){for(var worker:workers.values())try{worker.poll();}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Native task pool polling failed; other pools continue");}}
    @Override public int activeCount(){return workers.values().stream().mapToInt(TaskWorker::activeCount).sum();}
    public Map<String,Integer> activeByPool(){Map<String,Integer> out=new LinkedHashMap<>();workers.forEach((name,worker)->out.put(name,worker.activeCount()));return Collections.unmodifiableMap(out);}
    public Map<String,Integer> capacities(){return capacities;}
    @Override public void close(){workers.values().forEach(TaskWorker::close);}
}
