package com.tencent.weknora.chunker;

import java.util.List;

public record SplitterConfig(int chunkSize, int chunkOverlap, List<String> separators,
                             String strategy, int tokenLimit, List<String> languages) {
    public SplitterConfig {
        separators = separators == null ? List.of() : List.copyOf(separators);
        strategy = strategy == null ? "" : strategy;
        languages = languages == null ? List.of() : List.copyOf(languages);
    }
    public SplitterConfig(int size, int overlap, List<String> separators) {
        this(size, overlap, separators, "", 0, List.of());
    }
    public static SplitterConfig defaults() {
        return new SplitterConfig(512, 80, List.of("\n\n", "\n", "。"));
    }
    public SplitterConfig normalized() {
        return new SplitterConfig(chunkSize <= 0 ? 512 : chunkSize,
                chunkOverlap <= 0 ? 80 : chunkOverlap,
                separators.isEmpty() ? defaults().separators : separators,
                strategy, tokenLimit, languages);
    }
}
