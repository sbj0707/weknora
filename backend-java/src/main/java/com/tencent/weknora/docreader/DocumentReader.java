package com.tencent.weknora.docreader;

/** Native parser SPI for the ingestion pipeline; storage/indexing remain downstream. */
public interface DocumentReader {
    DocumentReadResult read(DocumentReadRequest request);
}
