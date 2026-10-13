package com.tencent.weknora.model.client;
import java.util.List;
public interface EmbeddingModel {
    String modelId();String modelName();int dimensions();
    float[][] embed(CallContext context,List<String> texts,boolean query);
    default float[] embed(CallContext context,String text,boolean query){return embed(context,List.of(text),query)[0];}
}
