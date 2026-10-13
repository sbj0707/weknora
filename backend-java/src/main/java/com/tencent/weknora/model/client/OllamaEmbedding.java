package com.tencent.weknora.model.client;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Production local Ollama BatchEmbed, with explicit invalid vector-shape rejection. */
public final class OllamaEmbedding implements EmbeddingModel {
    private final OllamaRuntime runtime;private final ModelEndpoint endpoint;private final int dimension,truncate;private final boolean override;private final String name;
    public OllamaEmbedding(OllamaRuntime runtime,ModelEndpoint endpoint,int dimension,int truncate,boolean override){this.runtime=runtime;this.endpoint=endpoint;this.dimension=dimension;this.truncate=truncate==0?511:truncate;this.override=override;this.name=endpoint.name.isEmpty()?"nomic-embed-text":endpoint.name;}
    @Override public String modelId(){return endpoint.id;}@Override public String modelName(){return name;}@Override public int dimensions(){return dimension;}
    public ObjectNode request(List<String> texts){ObjectNode out=SafeModelHttp.JSON.createObjectNode().put("model",name);out.set("input",SafeModelHttp.JSON.valueToTree(texts));ObjectNode options=SafeModelHttp.JSON.createObjectNode();if(truncate>0){options.put("num_ctx",truncate);out.put("truncate",true);}if(override&&dimension>0)out.put("dimensions",dimension);out.set("options",options);return out;}
    @Override public float[][] embed(CallContext call,List<String> texts,boolean query){runtime.ensure(call,name);JsonNode root;try{root=runtime.embed(call,request(texts));}catch(ModelClientException ex){throw new ModelClientException(endpoint.safe("failed to get embedding vectors: "+ex.getMessage()));}
        JsonNode rows=root.path("embeddings");if(rows.isMissingNode()||rows.isNull()||rows.isArray()&&rows.isEmpty())throw new ModelClientException("Ollama returned no embedding vectors");if(!rows.isArray())throw new ModelClientException("json: cannot unmarshal "+kind(rows)+" into Go struct field EmbedResponse.embeddings of type [][]float32");float[][] out=new float[rows.size()][];
        for(int i=0;i<out.length;i++){JsonNode row=rows.get(i);if(!row.isArray())throw new ModelClientException("Ollama returned an invalid embedding vector");out[i]=new float[row.size()];for(int j=0;j<row.size();j++){JsonNode value=row.get(j);if(!value.isNumber()&&!value.isNull())throw new ModelClientException("failed to get embedding vectors: json: cannot unmarshal "+kind(value)+" into Go struct field EmbedResponse.embeddings of type float32");out[i][j]=value.isNull()?0:Float.parseFloat(OllamaJson.numberLiteral(value));if(!Float.isFinite(out[i][j]))throw new ModelClientException("failed to get embedding vectors: json: cannot unmarshal number "+OllamaJson.numberLiteral(value)+" into Go struct field EmbedResponse.embeddings of type float32");}if(out[i].length==0)throw new ModelClientException("Ollama returned an empty embedding vector");}
        if(out.length!=texts.size())throw new ModelClientException("Ollama returned an embedding count mismatch");int size=out[0].length;for(float[] row:out)if(row.length!=size)throw new ModelClientException("Ollama returned inconsistent embedding dimensions");return out;
    }
    private static String kind(JsonNode node){return node.isObject()?"object":node.isArray()?"array":node.isTextual()?"string":node.isBoolean()?"bool":"number";}
}
