package com.tencent.weknora.knowledge;

/** Public document-tag cleanup admission, real Go handlers/SQL/Asynq options. */
class KnowledgeTagDocumentDeleteGoParityTest extends KnowledgeTagGoHttpParityTest {
  @Override String resource(){return "/go-tag-document-delete.json";}
  @Override int caseCount(){return 12;}
}
