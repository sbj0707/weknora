package com.tencent.weknora.model.client;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Actual Go openaiembeddings wire shape, dimensions opt-in and indexed placement. */
public final class OpenAIEmbedding implements EmbeddingModel {
    private final SafeModelHttp http;private final ModelEndpoint endpoint;private final int dimensions,requestedDimensions;
    public OpenAIEmbedding(SafeModelHttp http,ModelEndpoint endpoint,int dimensions,boolean override){this.http=http;this.endpoint=endpoint;this.dimensions=dimensions;this.requestedDimensions=override?dimensions:0;}
    @Override public String modelId(){return endpoint.id;}@Override public String modelName(){return endpoint.name;}@Override public int dimensions(){return dimensions;}
    public ObjectNode request(List<String> texts,boolean query){ObjectNode s=endpoint.settings,body=SafeModelHttp.JSON.createObjectNode();body.put("model",endpoint.wireModel);body.set("input",SafeModelHttp.JSON.valueToTree(texts));if(s.path("send_encoding_format").asBoolean())body.put("encoding_format","float");
        String field=s.path("dimensions_field").asText();if(!field.isEmpty()&&requestedDimensions>0)body.put(field,requestedDimensions);field=s.path("input_type_field").asText();if(!field.isEmpty()){String kind=query?"query":"document";body.put(field,s.path("input_type_values").path(kind).asText(kind));}
        field=s.path("truncate_field").asText();if(!field.isEmpty()){String value=s.path("truncate_value").asText();if(Set.of("true","false").contains(value))body.put(field,value.equals("true"));else body.put(field,value);}
        if(s.path("truncate_prompt_tokens").asInt()>0&&s.path("accepts_truncate_prompt_tokens").asBoolean())body.set("truncate_prompt_tokens",s.get("truncate_prompt_tokens"));s.path("extra_body").fields().forEachRemaining(e->{if(!body.has(e.getKey()))body.set(e.getKey(),e.getValue());});return body;
    }
    @Override public float[][] embed(CallContext context,List<String> texts,boolean query){if(texts.isEmpty())return new float[0][];List<ClientBatches.Batch> batches;
        try{batches=ClientBatches.split(texts,0,endpoint.settings.path("max_batch_size").asInt(),endpoint.settings.path("max_input_chars").asInt(),0);}catch(ModelClientException ex){throw new ModelClientException(endpoint.name+" embedding: "+ex.getMessage());}float[][] out=new float[texts.size()][];
        for(var batch:batches){context.check();ObjectNode response=endpoint.post(http,context,request(batch.items(),query),3);if(!response.path("error").path("message").asText().isEmpty())throw new ModelClientException(endpoint.safe("embedding API error: "+response.path("error").path("message").asText()));float[][] vectors=place(response.path("data"),batch.items().size());System.arraycopy(vectors,0,out,batch.start(),vectors.length);}return out;
    }
    public static float[][] place(JsonNode data,int want){if(!data.isArray())throw new ModelClientException("decode response: embedding data must be an array");float[][] out=new float[want][];boolean[] seen=new boolean[want];for(int i=0;i<data.size();i++){JsonNode item=data.get(i);if(item.hasNonNull("index")&&(!item.get("index").isIntegralNumber()||!item.get("index").canConvertToInt()))throw new ModelClientException("decode response: embedding index must be an integer");int index=item.hasNonNull("index")?item.path("index").asInt():i;if(index<0||index>=want)throw new ModelClientException("embedding index "+index+" out of range for "+want+" inputs");if(seen[index])throw new ModelClientException("embedding index "+index+" returned twice");seen[index]=true;
            JsonNode vector=item.path("embedding");if(vector.isNull()||vector.isMissingNode()){out[index]=new float[0];continue;}if(!vector.isArray())throw new ModelClientException("decode response: embedding must be an array");float[] values=new float[vector.size()];for(int j=0;j<vector.size();j++){JsonNode value=vector.get(j);if(!value.isNumber()&&!value.isNull())throw new ModelClientException("decode response: embedding must contain numbers");values[j]=value.isNull()?0:(float)value.asDouble();if(!Float.isFinite(values[j]))throw new ModelClientException("decode response: embedding value exceeds float32 range");}out[index]=values;
        }for(int i=0;i<want;i++)if(out[i]==null||out[i].length==0)throw new ModelClientException("no embedding returned for input "+i+" of "+want);return out;}
}
