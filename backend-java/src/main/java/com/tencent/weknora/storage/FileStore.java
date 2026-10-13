package com.tencent.weknora.storage;
import java.io.*;

/** Physical provider SPI; callers authorize tenant/resource ownership before invoking it. */
public interface FileStore {
    void checkConnectivity() throws IOException;
    String saveFile(InputStream input,String filename,long tenantId,String knowledgeId) throws IOException;
    String saveBytes(byte[] bytes,long tenantId,String filename,boolean temporary) throws IOException;
    InputStream open(String path) throws IOException;
    String url(String path) throws IOException;
    void delete(String path) throws IOException;
    String copy(String path,long tenantId,String knowledgeId) throws IOException;
}
