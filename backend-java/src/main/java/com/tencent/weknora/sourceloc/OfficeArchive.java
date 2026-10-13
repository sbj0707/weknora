package com.tencent.weknora.sourceloc;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** In-memory central-directory reads preserve archive order and the first matching duplicate. */
final class OfficeArchive {
  static final int MAX_BYTES = 64 << 20;

  record Entry(String name, int method, long compressed, long size, long crc, long offset) {}

  private final byte[] bytes;
  private final List<Entry> entries = new ArrayList<>();

  OfficeArchive(byte[] bytes) throws IOException {
    this.bytes = bytes;
    int eocd = -1;
    for (int i = bytes.length - 22; i >= Math.max(0, bytes.length - 22 - 65535); i--)
      if (u32(i) == 0x06054b50L && i + 22 + u16(i + 20) <= bytes.length) {
        eocd = i;
        break;
      }
    if (eocd < 0) throw malformed();
    long count = u16(eocd + 10),
        directory = u32(eocd + 16),
        directorySize = u32(eocd + 12),
        directoryEnd = eocd;
    if (count == 65535 || directory == 0xffffffffL) {
      int loc = eocd - 20;
      if (u32(loc) != 0x07064b50L) throw malformed();
      long pos = u64(loc + 8);
      int p = checked(pos, 56);
      if (u32(p) != 0x06064b50L) throw malformed();
      count = u64(p + 32);
      directorySize = u64(p + 40);
      directory = u64(p + 48);
      directoryEnd = p;
    }
    long baseOffset = directoryEnd - directorySize - directory;
    if (baseOffset < 0) throw malformed();
    if (directory <= Integer.MAX_VALUE && u32((int) directory) == 0x02014b50L) baseOffset = 0;
    if (count > Integer.MAX_VALUE || directory > bytes.length) throw malformed();
    int p = checked(directory + baseOffset, 0);
    for (long i = 0; i < count; i++) {
      if (u32(p) != 0x02014b50L) throw malformed();
      checked(p, 46);
      int nameSize = u16(p + 28), extraSize = u16(p + 30), commentSize = u16(p + 32);
      checked(p, 46 + nameSize + extraSize + commentSize);
      long compressed = u32(p + 20), size = u32(p + 24), offset = u32(p + 42);
      int extra = p + 46 + nameSize, end = extra + extraSize;
      for (int at = extra; at + 4 <= end; ) {
        int id = u16(at), length = u16(at + 2);
        int value = at + 4;
        if (value + length > end) throw malformed();
        if (id == 1) {
          if (size == 0xffffffffL) {
            if (value + 8 > at + 4 + length) throw malformed();
            size = u64(value);
            value += 8;
          }
          if (compressed == 0xffffffffL) {
            if (value + 8 > at + 4 + length) throw malformed();
            compressed = u64(value);
            value += 8;
          }
          if (offset == 0xffffffffL) {
            if (value + 8 > at + 4 + length) throw malformed();
            offset = u64(value);
          }
        }
        at += 4 + length;
      }
      entries.add(
          new Entry(
              new String(bytes, p + 46, nameSize, StandardCharsets.UTF_8),
              u16(p + 10),
              compressed,
              size,
              u32(p + 16),
              offset + baseOffset));
      p += 46 + nameSize + extraSize + commentSize;
    }
  }

  void verifySpreadsheetCapacity() throws OfficeStructure.UnsupportedCapability {
    for (var entry : entries)
      if (entry.size > MAX_BYTES)
        throw new OfficeStructure.UnsupportedCapability(
            "Excel ZIP members above 64 MiB require streaming extraction migration");
  }

  boolean contains(String name) {
    return entries.stream().anyMatch(e -> e.name.equals(name));
  }

  byte[] read(String name) throws IOException {
    Entry entry =
        entries.stream()
            .filter(e -> e.name.equals(name))
            .findFirst()
            .orElseThrow(() -> new IOException(name + ": not found"));
    if (entry.size > MAX_BYTES) throw new IOException(name + ": entry too large");
    int at = checked(entry.offset, 30);
    if (u32(at) != 0x04034b50L) throw malformed();
    int start = checked((long) at + 30 + u16(at + 26) + u16(at + 28), 0);
    checked(start, entry.compressed);
    InputStream input = new ByteArrayInputStream(bytes, start, Math.toIntExact(entry.compressed));
    Inflater inflater = null;
    if (entry.method == 8) {
      inflater = new Inflater(true);
      input = new InflaterInputStream(input, inflater);
    } else if (entry.method != 0) throw new IOException("zip: unsupported compression algorithm");
    try (InputStream stream = input) {
      var output = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int count;
      boolean eof = false;
      while (output.size() < MAX_BYTES) {
        count = stream.read(buffer, 0, Math.min(buffer.length, MAX_BYTES - output.size()));
        if (count < 0) {
          eof = true;
          break;
        }
        output.write(buffer, 0, count);
      }
      byte[] result = output.toByteArray();
      if (eof) {
        CRC32 crc = new CRC32();
        crc.update(result);
        if (result.length != entry.size || crc.getValue() != entry.crc)
          throw new IOException("zip: checksum error");
      }
      return result;
    } finally {
      if (inflater != null) inflater.end();
    }
  }

  private int checked(long pos, long length) throws IOException {
    if (pos < 0 || length < 0 || pos > bytes.length || length > bytes.length - pos)
      throw malformed();
    return (int) pos;
  }

  private int u16(int p) {
    if (p < 0 || p + 2 > bytes.length) return -1;
    return (bytes[p] & 255) | (bytes[p + 1] & 255) << 8;
  }

  private long u32(int p) {
    if (p < 0 || p + 4 > bytes.length) return -1;
    return Integer.toUnsignedLong(
        (bytes[p] & 255)
            | (bytes[p + 1] & 255) << 8
            | (bytes[p + 2] & 255) << 16
            | (bytes[p + 3] & 255) << 24);
  }

  private long u64(int p) throws IOException {
    long low = u32(p), high = u32(p + 4);
    if (low < 0 || high < 0 || high > 0x7fffffffL) throw malformed();
    return high << 32 | low;
  }

  private static IOException malformed() {
    return new IOException("zip: not a valid zip file");
  }
}
