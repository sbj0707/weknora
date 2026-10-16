package com.tencent.weknora.knowledge;

import com.tencent.weknora.docreader.GoUtf8;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/**
 * TaskTenantID deliberately uses Go's tenant/timestamp search, rather than splitting a fixed
 * prefix.
 */
final class KnowledgeMoveTaskIds {
  static String generate(long owner, String source, Clock clock) {
    byte[] raw = source.getBytes(StandardCharsets.UTF_8);
    String business =
        GoUtf8.decode(Arrays.copyOf(raw, Math.min(12, raw.length)))
            .replace("-", "")
            .replace("_", "")
            .replace(":", "");
    return "kg_move_"
        + owner
        + "_"
        + clock.millis()
        + "_"
        + UUID.randomUUID().toString().substring(0, 8)
        + (business.isEmpty() ? "" : "_" + business);
  }

  static BigInteger tenant(String task) {
    String[] parts = task.split("_", -1);
    if (parts.length >= 4)
      for (int i = 1; i < parts.length - 2; i++)
        try {
          if (!parts[i].matches("[0-9]+") || !parts[i + 1].matches("[+-]?[0-9]+")) continue;
          BigInteger owner = new BigInteger(parts[i]);
          long timestamp = Long.parseLong(parts[i + 1]);
          if (owner.signum() > 0 && owner.bitLength() <= 64 && timestamp >= 1000000000000L)
            return owner;
        } catch (NumberFormatException ignored) {
        }
    throw KnowledgeMoveRequest.bad("invalid task ID");
  }
}
