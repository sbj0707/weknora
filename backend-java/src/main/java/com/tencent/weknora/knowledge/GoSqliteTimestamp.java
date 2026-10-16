package com.tencent.weknora.knowledge;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * go-sqlite3 SQLiteTimestampFormats[0]; persisted DATETIME must round-trip into a CAS predicate.
 */
final class GoSqliteTimestamp {
  private static final DateTimeFormatter SECONDS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

  static String write(Instant value) {
    String text = SECONDS.format(value);
    if (value.getNano() != 0)
      text += "." + String.format(Locale.ROOT, "%09d", value.getNano()).replaceFirst("0+$", "");
    return text + "+00:00";
  }

  private GoSqliteTimestamp() {}
}
