package com.tencent.weknora.chunker;

/** Positions are Unicode code point offsets, as in Go's []rune. */
public record Chunk(String content, String contextHeader, int seq, int start, int end) {
    public String embeddingContent() {
        String body = RuneText.trim(content);
        return contextHeader.isEmpty() ? body : contextHeader + "\n\n" + body;
    }
}
