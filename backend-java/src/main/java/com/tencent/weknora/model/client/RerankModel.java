package com.tencent.weknora.model.client;
import java.util.List;
public interface RerankModel {
    record Result(int index,String text,double relevanceScore){}
    String modelId();String modelName();int maxPassageRunes(String query);
    List<Result> rerank(CallContext context,String query,List<String> documents);
}
