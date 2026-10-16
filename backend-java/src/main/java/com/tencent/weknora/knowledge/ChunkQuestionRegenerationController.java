package com.tencent.weknora.knowledge;

import com.tencent.weknora.security.CurrentAuth;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="weknora.knowledge.processing.enabled",havingValue="true")
public final class ChunkQuestionRegenerationController {
  private final ChunkQuestionService questions;private final ChunkQuestionRegenerationService generation;private final KnowledgeProcessingLanguage language;
  public ChunkQuestionRegenerationController(ChunkQuestionService questions,ChunkQuestionRegenerationService generation,KnowledgeProcessingLanguage language){this.questions=questions;this.generation=generation;this.language=language;}
  @PostMapping("/api/v1/chunks/by-id/{id}/questions/regenerate")
  public Map<String,Object> regenerate(HttpServletRequest request,@PathVariable String id){var grant=questions.authorize(CurrentAuth.get(request),id,request.getRequestURI(),"POST");String clean=KnowledgeImportNames.sanitizeForLog(id);if(clean.isEmpty())throw ChunkQuestionRequest.bad("Chunk ID is required");return generation.regenerate(grant,clean,language.resolve(request));}
}
