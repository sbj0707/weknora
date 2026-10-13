package com.tencent.weknora.docreader;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Shared lifecycle and engine discovery for the configured remote parser. */
public interface RemoteDocumentReader extends DocumentReader, AutoCloseable {
    void reconnect(String address) throws IOException;
    boolean isConnected();
    List<DocumentReadResult.ParserEngineInfo> listEngines(Map<String, String> overrides);
    @Override void close();
}
