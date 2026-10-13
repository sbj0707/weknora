package com.tencent.weknora.model.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

final class ClientBatches {
    record Batch(int start,List<String> items){}
    static int runes(String text){return text.codePointCount(0,text.length());}
    static List<Batch> split(List<String> items,int fixed,int maxItems,int maxItem,int maxTotal){
        if(maxTotal>0&&fixed>=maxTotal)throw new ModelClientException("query is "+fixed+" characters; the request budget is "+maxTotal+" and must also fit a document");
        int[] sizes=new int[items.size()];for(int i=0;i<items.size();i++){sizes[i]=runes(items.get(i));if(maxItem>0&&sizes[i]>maxItem)throw new ModelClientException("document at index "+i+" is "+sizes[i]+" characters; the per-document limit is "+maxItem);if(maxTotal>0&&fixed+sizes[i]>maxTotal)throw new ModelClientException("document at index "+i+" is "+sizes[i]+" characters; it does not fit beside a "+fixed+"-character query in the "+maxTotal+"-character request budget");}
        if(items.isEmpty())return List.of();List<Batch> out=new ArrayList<>();int start=0,total=fixed;for(int i=0;i<items.size();i++){if(i>start&&(maxItems>0&&i-start==maxItems||maxTotal>0&&total+sizes[i]>maxTotal)){out.add(new Batch(start,List.copyOf(items.subList(start,i))));start=i;total=fixed;}total+=sizes[i];}out.add(new Batch(start,List.copyOf(items.subList(start,items.size()))));return out;
    }
    static List<Batch> split(List<String> items,int fixed,JsonNode settings){return split(items,fixed,settings.path("max_documents").asInt(),settings.path("max_document_chars").asInt(),settings.path("max_request_chars").asInt());}
}
