package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.knowledge.KnowledgeContentReadRepository;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Resolves global child identifiers to a parent KB read grant, without promoting operation
 * permission.
 */
@Service
public final class KnowledgeContentReadAuthorization {
  public record KnowledgeAccess(
      ObjectNode knowledge, KnowledgeBaseReadAuthorization.ReadAccess parent) {}

  public record ChunkAccess(ObjectNode chunk, KnowledgeBaseReadAuthorization.ReadAccess parent) {}

  private final KnowledgeContentReadRepository repository;
  private final KnowledgeBaseReadAuthorization reads;

  public KnowledgeContentReadAuthorization(
      KnowledgeContentReadRepository repository, KnowledgeBaseReadAuthorization reads) {
    this.repository = repository;
    this.reads = reads;
  }

  public KnowledgeAccess authorizeKnowledge(
      AuthContext caller, String id, String agentId, String sourceSelector) {
    requireRetrieve(caller);
    ObjectNode knowledge = lookupKnowledge(id);
    return new KnowledgeAccess(
        knowledge,
        reads.authorizeRead(
            caller, knowledge.path("knowledge_base_id").asText(), agentId, sourceSelector));
  }

  public ChunkAccess authorizeChunk(
      AuthContext caller, String id, String agentId, String sourceSelector) {
    requireRetrieve(caller);
    ObjectNode chunk = lookupChunk(id);
    String kb = chunk.path("knowledge_base_id").asText();
    if (kb.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Chunk not found").withAppCode(1003);
    return new ChunkAccess(chunk, reads.authorizeRead(caller, kb, agentId, sourceSelector));
  }

  public ObjectNode lookupKnowledge(String id) {
    try {
      return repository.globalKnowledge(id);
    } catch (ApiException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw resolverFailure();
    }
  }

  private ObjectNode lookupChunk(String id) {
    try {
      return repository.globalChunk(id);
    } catch (ApiException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw resolverFailure();
    }
  }

  public static void requireRetrieve(AuthContext caller) {
    if (caller.isApiKey() && !caller.fullAccess() && !caller.capabilities().contains("retrieve"))
      throw new ApiException(
              403, "FORBIDDEN", "Forbidden: API key scope does not allow this operation")
          .withResponse(Map.of("error", "Forbidden: API key scope does not allow this operation"));
  }

  private static ApiException resolverFailure() {
    return new ApiException(500, "INTERNAL_SERVER_ERROR", "Internal server error")
        .withResponse(
            Map.of(
                "success",
                false,
                "error",
                Map.of("code", 1007, "message", "Internal server error")));
  }
}
