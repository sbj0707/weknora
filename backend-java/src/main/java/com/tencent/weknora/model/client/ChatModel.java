package com.tencent.weknora.model.client;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.function.Consumer;
/** Neutral messages/options use the existing Go api.Message/api.Options field contract. */
public interface ChatModel {
    String modelId();String modelName();
    ObjectNode chat(CallContext context,List<ObjectNode> messages,ObjectNode options);
    void stream(CallContext context,List<ObjectNode> messages,ObjectNode options,Consumer<ObjectNode> consumer);
}
