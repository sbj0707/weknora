package com.tencent.weknora.docreader;

import com.google.protobuf.ByteString;
import docreader.Docreader;
import java.util.Map;

/** Transport-independent file/URL request, matching internal/types.ReadRequest. */
public record DocumentReadRequest(byte[] fileContent, String fileName, String fileType, String url,
                                  String title, String parserEngine, String requestId,
                                  Map<String, String> parserEngineOverrides) {
    public DocumentReadRequest {
        fileContent = fileContent == null ? new byte[0] : fileContent.clone();
        fileName = text(fileName); fileType = text(fileType); url = text(url); title = text(title);
        parserEngine = text(parserEngine); requestId = text(requestId);
        parserEngineOverrides = parserEngineOverrides == null ? Map.of() : Map.copyOf(parserEngineOverrides);
    }
    @Override public byte[] fileContent() { return fileContent.clone(); }
    Docreader.ReadRequest toProto() {
        return Docreader.ReadRequest.newBuilder().setFileContent(ByteString.copyFrom(fileContent))
                .setFileName(fileName).setFileType(fileType).setUrl(url).setTitle(title).setRequestId(requestId)
                .setConfig(Docreader.ReadConfig.newBuilder().setParserEngine(parserEngine)
                        .putAllParserEngineOverrides(parserEngineOverrides)).build();
    }
    private static String text(String value) { return value == null ? "" : value; }
}
