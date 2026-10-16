package com.tencent.weknora.knowledge;

/** Same actual Go production producer oracle against real PostgreSQL queue/savepoint failures. */
class ImageMultimodalFanoutPostgresGoParityIT extends ImageMultimodalFanoutGoParityTest {
  private final ImageMultimodalPostgresGoParityIT database =
      new ImageMultimodalPostgresGoParityIT();

  @Override
  DocumentProcessingCompatibilityTest database(int no, String name) throws Exception {
    database.temp = temp;
    return database.database(no, name);
  }

  @Override
  void release(DocumentProcessingCompatibilityTest f) {
    database.release(f);
  }
}
