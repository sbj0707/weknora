package com.tencent.weknora.vectorstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.model.ModelEgressPolicy;
import com.tencent.weknora.model.client.SafeModelHttp;
import com.tencent.weknora.security.GoSecretCodec;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class VectorStoreConfiguration {
  @Bean VectorStoreMetadata vectorStoreMetadata(JdbcTemplate jdbc,ObjectMapper json,GoSecretCodec secrets){return new VectorStoreMetadata(jdbc,json,secrets);}
  @Bean(destroyMethod="close") VectorStoreHttpRuntime vectorStoreHttpRuntime(ModelEgressPolicy policy){return new VectorStoreHttpRuntime(policy);}
  @Bean(destroyMethod="close") VectorStoreRegistry vectorStoreRegistry(VectorStoreMetadata metadata,VectorStoreHttpRuntime runtime,ObjectMapper json){return new VectorStoreRegistry(metadata,(store,call)->{if(!store.engine().equals("elasticsearch"))throw new IllegalArgumentException("Native bound retrieval engine is not migrated");return new Elasticsearch8Store(store,runtime.http,json,call);});}
  public static final class VectorStoreHttpRuntime implements AutoCloseable {final SafeModelHttp http;VectorStoreHttpRuntime(ModelEgressPolicy policy){http=new SafeModelHttp(policy);}@Override public void close(){http.close();}}
}
