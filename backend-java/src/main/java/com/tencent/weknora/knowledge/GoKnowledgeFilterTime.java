package com.tencent.weknora.knowledge;

import com.tencent.weknora.security.ApiException;
import com.tencent.weknora.storage.StoragePaths;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.regex.*;

/**
 * Implements the existing Go knowledge-list filter layouts, including their permissive fractions.
 */
public final class GoKnowledgeFilterTime {
  private static final Pattern RFC =
      Pattern.compile(
          "([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{1,2}):([0-9]{2}):([0-9]{2})(?:[.,]([0-9]+))?(Z|[+-][0-9]{2}:[0-9]{2})");
  private static final Pattern LOCAL =
      Pattern.compile(
          "([0-9]{4})-([0-9]{2})-([0-9]{2}) +([0-9]{1,2}):([0-9]{2}):([0-9]{2})(?:[.,]([0-9]+))?");
  private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
  private static final Instant ZERO = Instant.parse("0001-01-01T00:00:00Z");

  private GoKnowledgeFilterTime() {}

  public static Instant parse(String value, String name) {
    String raw = StoragePaths.trim(value);
    if (raw.isEmpty()) return null;
    try {
      var rfc = RFC.matcher(raw);
      if (rfc.matches()) {
        var local = dateTime(rfc);
        String zone = rfc.group(8);
        long offset = 0;
        if (!zone.equals("Z")) {
          int hour = Integer.parseInt(zone.substring(1, 3)),
              minute = Integer.parseInt(zone.substring(4, 6));
          if (hour > 24 || minute > 60) throw new DateTimeException("Invalid offset");
          offset = (hour * 60L + minute) * 60L * (zone.charAt(0) == '-' ? -1 : 1);
        }
        return absentZero(
            Instant.ofEpochSecond(local.toEpochSecond(ZoneOffset.UTC) - offset, local.getNano()));
      }
      var local = LOCAL.matcher(raw);
      if (local.matches()) return absentZero(localInstant(dateTime(local)));
      if (DATE.matcher(raw).matches())
        return absentZero(localInstant(LocalDate.parse(raw).atStartOfDay()));
    } catch (DateTimeException | NumberFormatException ignored) {
    }
    throw bad("invalid " + name + ": " + dateError(raw));
  }

  private static Instant absentZero(Instant instant) {
    return instant.equals(ZERO) ? null : instant;
  }

  private static LocalDateTime dateTime(Matcher m) {
    String fraction = m.group(7);
    int nano = fraction == null ? 0 : Integer.parseInt((fraction + "000000000").substring(0, 9));
    return LocalDateTime.of(
        Integer.parseInt(m.group(1)),
        Integer.parseInt(m.group(2)),
        Integer.parseInt(m.group(3)),
        Integer.parseInt(m.group(4)),
        Integer.parseInt(m.group(5)),
        Integer.parseInt(m.group(6)),
        nano);
  }

  private static Instant localInstant(LocalDateTime local) {
    // Go Date first looks up the local wall-clock value as UTC, then adjusts once across a
    // transition.
    var naive = local.toInstant(ZoneOffset.UTC);
    var rules = ZoneId.systemDefault().getRules();
    int offset = rules.getOffset(naive).getTotalSeconds();
    int resolved = rules.getOffset(naive.minusSeconds(offset)).getTotalSeconds();
    return naive.minusSeconds(resolved);
  }

  private static ApiException bad(String message) {
    return new ApiException(400, "BAD_REQUEST", message).withAppCode(1000);
  }

  private static String dateError(String raw) {
    String layout = "2006-01-02";
    int index = 0;
    String[] fields = {"2006", "-", "01", "-", "02"};
    int year = 0, month = 0, day = 0;
    for (String field : fields) {
      int len = field.equals("2006") ? 4 : field.equals("-") ? 1 : 2;
      String remaining = raw.substring(Math.min(index, raw.length()));
      if (remaining.length() < len
          || (field.equals("-")
              ? !remaining.startsWith("-")
              : !remaining
                  .substring(0, Math.min(len, remaining.length()))
                  .matches("[0-9]{" + len + "}")))
        return "parsing time "
            + quoteTime(raw)
            + " as "
            + quoteTime(layout)
            + ": cannot parse "
            + quoteTime(remaining)
            + " as "
            + quoteTime(field);
      if (!field.equals("-")) {
        int value = Integer.parseInt(remaining.substring(0, len));
        if (index == 0) year = value;
        else if (index == 5) month = value;
        else day = value;
        if (index == 5 && (month < 1 || month > 12))
          return "parsing time " + quoteTime(raw) + ": month out of range";
      }
      index += len;
    }
    if (index < raw.length())
      return "parsing time " + quoteTime(raw) + ": extra text: " + quoteTime(raw.substring(index));
    try {
      LocalDate.of(year, month, day);
    } catch (Exception ex) {
      return "parsing time " + quoteTime(raw) + ": day out of range";
    }
    return "parsing time "
        + quoteTime(raw)
        + " as "
        + quoteTime(layout)
        + ": cannot parse "
        + quoteTime(raw)
        + " as "
        + quoteTime(layout);
  }

  private static String quoteTime(String value) {
    StringBuilder quoted = new StringBuilder("\"");
    for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
      int b = item & 255;
      if (b >= 128 || b < 32)
        quoted
            .append("\\x")
            .append("0123456789abcdef".charAt(b >> 4))
            .append("0123456789abcdef".charAt(b & 15));
      else {
        if (b == '"' || b == '\\') quoted.append('\\');
        quoted.append((char) b);
      }
    }
    return quoted.append('"').toString();
  }
}
