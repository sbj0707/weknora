package com.tencent.weknora.model.client;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.function.Consumer;

/** Go protocol-neutral event assembly, cancellation checks and stable tool index ordering. */
final class ModelStreamAssembler {
    record ToolDelta(int index,String id,String type,String name,String arguments){}
    private final CallContext context;private final Consumer<ObjectNode> consumer;private final ModelEndpoint endpoint;
    private final SortedMap<Integer,ObjectNode> tools=new TreeMap<>();private final Map<Integer,String> lastNames=new HashMap<>();private final Set<Integer> notified=new HashSet<>();
    private final Map<Integer,JSONFieldExtractor> thoughts=new HashMap<>();private final Map<Integer,SandboxFileProgress> progress=new HashMap<>();
    private boolean thinking;private String finish="",signature="";private ObjectNode usage,metadata;
    ModelStreamAssembler(CallContext context,Consumer<ObjectNode> consumer,ModelEndpoint endpoint){this.context=context;this.consumer=consumer;this.endpoint=endpoint;}
    void signature(String value){signature=value;}
    void replaceCalls(ArrayNode calls){tools.clear();for(int i=0;i<calls.size();i++)tools.put(i,(ObjectNode)calls.get(i).deepCopy());}
    int toolCount(){return tools.size();}void usage(ObjectNode value){usage=value.deepCopy();}void reasoning(ObjectNode value){metadata=value==null?null:value.deepCopy();}
    private ObjectNode entry(int index){return tools.computeIfAbsent(index,n->SafeModelHttp.JSON.createObjectNode().put("id","").put("type","function").set("function",SafeModelHttp.JSON.createObjectNode().put("name","").put("arguments","")));}
    void metadata(int index,ObjectNode value){if(value!=null&&!value.isEmpty())entry(index).set("provider_metadata",value.deepCopy());}
    private ArrayNode ordered(){ArrayNode out=SafeModelHttp.JSON.createArrayNode();tools.values().forEach(t->out.add(t.deepCopy()));return out;}
    private ObjectNode event(String type,String content,boolean done){return SafeModelHttp.JSON.createObjectNode().put("id","").put("response_type",type).put("content",content).put("done",done);}
    private ObjectNode withCalls(ObjectNode event){ArrayNode calls=ordered();if(!calls.isEmpty())event.set("tool_calls",calls);if(!finish.isEmpty())event.put("finish_reason",finish);return event;}
    private void emit(ObjectNode event){context.check();consumer.accept(event);context.check();}
    private void finishThinking(){if(thinking){thinking=false;emit(event("thinking","",true));}}
    void process(String reasoning,String content,List<ToolDelta> deltas,String reason){boolean done=!reason.isEmpty();if(done)finish=reason;for(ToolDelta delta:deltas){ObjectNode current=entry(delta.index);if(!delta.id.isEmpty())current.put("id",delta.id);if(!delta.type.isEmpty())current.put("type",delta.type);ObjectNode function=(ObjectNode)current.get("function");if(!delta.name.isEmpty()&&!function.path("name").asText().equals(delta.name))function.put("name",function.path("name").asText()+delta.name);if(!delta.arguments.isEmpty())function.put("arguments",function.path("arguments").asText()+delta.arguments);
        String name=function.path("name").asText();ObjectNode statistics=null;if(SandboxFileProgress.supported(name)&&!delta.arguments.isEmpty())statistics=progress.computeIfAbsent(delta.index,k->new SandboxFileProgress(name)).feed(delta.arguments);
        if(!name.isEmpty()&&name.equals(lastNames.get(delta.index))&&!delta.arguments.isEmpty()&&!notified.contains(delta.index)&&!current.path("id").asText().isEmpty()){ObjectNode data=SafeModelHttp.JSON.createObjectNode().put("tool_name",name).put("tool_call_id",current.path("id").asText());if(statistics!=null)data.set("arguments",statistics);emit(event("tool_call","",false).set("data",data));notified.add(delta.index);statistics=null;}else if(statistics!=null&&!current.path("id").asText().isEmpty()&&!name.isEmpty())emit(event("tool_call","",false).set("data",SafeModelHttp.JSON.createObjectNode().put("tool_name",name).put("tool_call_id",current.path("id").asText()).set("arguments",statistics)));lastNames.put(delta.index,name);
        if(name.equals("thinking")&&!delta.arguments.isEmpty()){String thought=thoughts.computeIfAbsent(delta.index,k->new JSONFieldExtractor("thought")).feed(delta.arguments);if(!thought.isEmpty())emit(event("thinking",thought,false).set("data",SafeModelHttp.JSON.createObjectNode().put("source","thinking_tool").put("tool_call_id",current.path("id").asText())));}
    }
        if(!reasoning.isEmpty()){thinking=true;emit(event("thinking",reasoning,false));}if(!content.isEmpty()){finishThinking();ObjectNode answer=withCalls(event("answer",content,done));if(reason.isEmpty())answer.remove("finish_reason");emit(answer);}if(done&&!tools.isEmpty())emit(withCalls(event("answer","",true)));if(done)finishThinking();if(done&&content.isEmpty()&&tools.isEmpty())emit(withCalls(event("answer","",true)));
    }
    void endAtEOF(){if(finish.isEmpty())finish="incomplete";end();}
    void end(){finishThinking();ObjectNode end=withCalls(event("answer","",true));if(usage!=null)end.set("usage",usage.deepCopy());if(!signature.isEmpty()||metadata!=null&&!metadata.isEmpty()){ObjectNode data=SafeModelHttp.JSON.createObjectNode();if(!signature.isEmpty())data.put("reasoning_signature",signature);if(metadata!=null&&!metadata.isEmpty())data.set("reasoning_metadata",metadata.deepCopy());end.set("data",data);}emit(end);}
    void fail(String message){finishThinking();finish="incomplete";ObjectNode error=withCalls(event("error",endpoint.safe(message),true));if(usage!=null)error.set("usage",usage.deepCopy());emit(error);}
}
