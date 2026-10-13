package com.tencent.weknora.retrieval;

import java.util.List;
public record RetrievalQuery(String query,float[] embedding,List<String> knowledgeBaseIds,
                             List<String> knowledgeIds,List<String> tagIds,int topK,double threshold,String kind) {
    public RetrievalQuery {
        query=query==null?"":query;embedding=embedding==null?new float[0]:embedding.clone();
        knowledgeBaseIds=knowledgeBaseIds==null?List.of():List.copyOf(knowledgeBaseIds);
        knowledgeIds=knowledgeIds==null?List.of():List.copyOf(knowledgeIds);
        tagIds=tagIds==null?List.of():List.copyOf(tagIds);kind=kind==null?"":kind;
    }
    @Override public float[] embedding(){return embedding.clone();}
}
