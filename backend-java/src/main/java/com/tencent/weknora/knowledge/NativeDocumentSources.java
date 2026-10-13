package com.tencent.weknora.knowledge;

import com.tencent.weknora.docreader.DocumentReadResult;
import com.tencent.weknora.sourceloc.*;
import com.tencent.weknora.task.TaskFailure;
import java.util.*;

/**
 * Bind original-file references, align CSV rows and retain coordinates through Markdown rewrites.
 */
public final class NativeDocumentSources {
  private NativeDocumentSources() {}

  public static SourceLocatorIndex build(
      DocumentReadResult read, String finalMarkdown, String type, String hash, byte[] original)
      throws TaskFailure {
    String normalized = com.tencent.weknora.docreader.ParserConfiguration.normalizeFileType(type);
    List<DocumentReadResult.SourceBlock> blocks = read.sourceBlocks();
    if (blocks.isEmpty()
        && Set.of("txt", "text", "md", "markdown").contains(normalized)
        && Arrays.equals(
            original, read.markdownContent().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      blocks = SourceLocatorIndex.passThroughTextBlocks(read.markdownContent());
    if (blocks.isEmpty() && normalized.equals("csv"))
      blocks = SourceAlignment.align(read.markdownContent(), SourceAlignment.csvUnits(original));
    if (blocks.isEmpty()
        && OfficeStructure.supports(normalized)
        && !normalized.equals("csv")
        && original.length > 0
        && !read.markdownContent().isEmpty()) {
      try {
        blocks = OfficeStructure.align(normalized, original, read.markdownContent());
      } catch (OfficeStructure.UnsupportedCapability unsupported) {
        throw new TaskFailure(
            "Native document structure capability is not migrated for " + normalized, false);
      } catch (java.io.IOException damaged) {
        // Go attachment is best effort for damaged supported Office files; never invent
        // coordinates.
        blocks = List.of();
      }
    }
    List<DocumentReadResult.SourceBlock> bound = new ArrayList<>();
    for (var block : blocks) {
      Map<String, Object> locator = new LinkedHashMap<>(block.locator());
      locator.put("source_hash", hash);
      bound.add(new DocumentReadResult.SourceBlock(block.start(), block.end(), locator));
    }
    if (!read.markdownContent().equals(finalMarkdown))
      bound = SourceAlignment.remapBlocks(bound, read.markdownContent(), finalMarkdown);
    return new SourceLocatorIndex(finalMarkdown, bound);
  }
}
