package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.model.ModelClientFactory;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.vectorstore.VectorStoreRegistry;
import com.tencent.weknora.security.KnowledgeBaseReadAuthorization;
import com.tencent.weknora.storage.StorageResolver;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/** Public reads are available independently of the opt-in document/task workers. */
@Configuration
public class HybridSearchConfiguration {
  @Bean HybridSearchStore hybridSearchStore(DataSource dataSource,Environment environment,VectorStoreRegistry registry){return new HybridSearchStore(dataSource,environment).withRegistry(registry);}
  @Bean HybridSearchHydration hybridSearchHydration(JdbcTemplate jdbc,ObjectMapper json,GraphQueryHydration projection){return new HybridSearchHydration(jdbc,json,projection);}
  @Bean HybridSearchResourceURLs hybridSearchResourceURLs(StorageResolver storage,Environment environment){return new HybridSearchResourceURLs(storage,environment);}
  @Bean(destroyMethod="close") HybridSearchRerankRuntime hybridSearchRerankRuntime(Environment environment){return new HybridSearchRerankRuntime(environment);}
  @Bean HybridSearchService hybridSearchService(JdbcTemplate jdbc,ObjectMapper json,KnowledgeBaseReadAuthorization authorization,ModelClientFactory models,HybridSearchStore store,HybridSearchHydration hydration,HybridSearchRerankRuntime runtime){return new HybridSearchService(jdbc,json,authorization,models,store,hydration,runtime.stage());}
  static final class HybridSearchRerankRuntime implements AutoCloseable {
    private final GoJiebaSegmenter segmenter;
    HybridSearchRerankRuntime(Environment environment){String library=environment.getProperty("weknora.jieba.library",environment.getProperty("WEKNORA_JIEBA_LIBRARY","")),dict=environment.getProperty("weknora.jieba.dict",environment.getProperty("WEKNORA_JIEBA_DICT",""));segmenter=library.isEmpty()||dict.isEmpty()?null:new GoJiebaSegmenter(Path.of(library),Path.of(dict));}
    RerankStage stage(){return new RerankStage(segmenter);}
    @Override public void close(){if(segmenter!=null)segmenter.close();}
  }
}
