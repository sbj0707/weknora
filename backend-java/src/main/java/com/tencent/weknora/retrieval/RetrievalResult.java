package com.tencent.weknora.retrieval;
import java.util.List;
public record RetrievalResult(List<RetrievalHit> results,String engine,String kind) {
    public RetrievalResult { results=List.copyOf(results); }
}
