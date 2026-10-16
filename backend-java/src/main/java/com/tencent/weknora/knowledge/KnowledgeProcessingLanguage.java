package com.tencent.weknora.knowledge;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Deployment language overrides the UI locale, matching Go Language middleware. */
@Component
public final class KnowledgeProcessingLanguage {
  private final String configured;
  public KnowledgeProcessingLanguage(Environment environment) {
    configured=KnowledgeUploadForms.goTrim(environment.getProperty("WEKNORA_LANGUAGE",""));
  }
  public String resolve(HttpServletRequest request) {
    return resolve(request.getHeader("Accept-Language"));
  }
  public String resolve(String header) {
    if(!configured.isEmpty())return configured;
    String tag=KnowledgeUploadForms.goTrim(Objects.toString(header,"").split(",",2)[0].split(";",2)[0]);
    return tag.isEmpty()?"zh-CN":tag;
  }
}
