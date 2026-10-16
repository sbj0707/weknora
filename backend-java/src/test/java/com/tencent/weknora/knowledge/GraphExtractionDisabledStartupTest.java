package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;

/** Driver 6 must not trigger Boot 3.5's incompatible driver factory in ordinary bridge/read startup. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.datasource.driver-class-name=org.sqlite.JDBC","weknora.jwt-secret=isolated-graph-disabled-startup-secret","weknora.bridge.enabled=false","weknora.knowledge.processing.enabled=false","weknora.tasks.enabled=false","NEO4J_ENABLE=false","NEO4J_URI=bolt://127.0.0.1:1","logging.level.root=WARN"})
class GraphExtractionDisabledStartupTest {
  @Autowired ApplicationContext context;@Autowired Neo4jGraphRepository graph;@Autowired TestRestTemplate http;
  @Test void disabledGraphAndProcessingStartWithoutAHiddenDriverOrGraphWorker(){assertFalse(graph.enabled());assertTrue(context.getBeansOfType(Driver.class).isEmpty());assertTrue(context.getBeansOfType(GraphExtractionHandler.class).isEmpty());assertTrue(context.getBeansOfType(DocumentProcessingHandler.class).isEmpty());var health=http.getForEntity("/health",Map.class);assertTrue(health.getStatusCode().is2xxSuccessful(),health.toString());assertEquals("ok",Objects.requireNonNull(health.getBody()).get("status"));}
}
