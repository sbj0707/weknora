package com.tencent.weknora.model.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Go context cache fallback and non-mutating image removal for a single retry. */
final class ChatInputs {
    private ChatInputs(){}
    static ObjectNode options(CallContext context,ObjectNode opts){if(opts!=null&&!opts.path("prompt_cache_key").asText().isEmpty()||context.sessionId().isEmpty())return opts;ObjectNode result=opts==null?SafeModelHttp.JSON.createObjectNode():opts.deepCopy();return result.put("prompt_cache_key",context.sessionId());}
    static List<ObjectNode> stripImages(List<ObjectNode> messages){List<ObjectNode> out=new ArrayList<>(messages.size());for(ObjectNode message:messages){ObjectNode copy=message.deepCopy();copy.remove("images");if(copy.path("multi_content").isArray()){ArrayNode kept=SafeModelHttp.JSON.createArrayNode();for(JsonNode part:copy.path("multi_content"))if(!part.path("type").asText().equals("image_url")&&!part.hasNonNull("image_url"))kept.add(part.deepCopy());if(kept.isEmpty())copy.remove("multi_content");else copy.set("multi_content",kept);}out.add(copy);}return out;}
}
