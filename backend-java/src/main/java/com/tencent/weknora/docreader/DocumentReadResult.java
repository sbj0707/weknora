package com.tencent.weknora.docreader;

import java.util.List;
import java.util.Map;

/** Parser errors remain data, as in Go; transport failures retain their gRPC status. */
public record DocumentReadResult(String markdownContent, List<ImageRef> imageRefs, String imageDirPath,
                                 Map<String, String> metadata, String error, List<SourceBlock> sourceBlocks,
                                 boolean isAudio, byte[] audioData) {
    public DocumentReadResult(String markdownContent, List<ImageRef> imageRefs, String imageDirPath,
                              Map<String, String> metadata, String error, List<SourceBlock> sourceBlocks) {
        this(markdownContent, imageRefs, imageDirPath, metadata, error, sourceBlocks, false, new byte[0]);
    }
    public DocumentReadResult {
        imageRefs = List.copyOf(imageRefs);
        metadata = Map.copyOf(metadata);
        sourceBlocks = List.copyOf(sourceBlocks);
        audioData = audioData == null ? new byte[0] : audioData.clone();
    }
    @Override public byte[] audioData() { return audioData.clone(); }
    public record ImageRef(String filename, String originalRef, String mimeType, String storageKey, byte[] imageData, boolean isOriginal) {
        public ImageRef(String filename, String originalRef, String mimeType, String storageKey, byte[] imageData) {
            this(filename, originalRef, mimeType, storageKey, imageData, false);
        }
        public ImageRef { imageData = imageData.clone(); }
        @Override public byte[] imageData() { return imageData.clone(); }
    }
    /** Offsets are Unicode code points, never Java UTF-16 char indexes. */
    public record SourceBlock(long start, long end, Map<String, Object> locator) {
        public SourceBlock { locator = Map.copyOf(locator); }
    }
    public record ParserEngineInfo(String name, String description, List<String> fileTypes,
                                    boolean available, String unavailableReason,
                                    @com.fasterxml.jackson.annotation.JsonIgnore boolean fileTypesNil) {
        public ParserEngineInfo(String name,String description,List<String> fileTypes,boolean available,String reason) {
            this(name,description,fileTypes,available,reason,false);
        }
        public ParserEngineInfo { fileTypes = List.copyOf(fileTypes); }
    }
}
