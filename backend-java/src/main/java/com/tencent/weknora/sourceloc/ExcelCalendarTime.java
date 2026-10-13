// Go 1.26 UTC calendar arithmetic used by Excelize; BSD 3-Clause.
// Copyright (c) 2009 The Go Authors. See META-INF/licenses/GO-HTML-UNICODE-LICENSE.txt.
package com.tencent.weknora.sourceloc;

import java.math.BigInteger;

final class ExcelCalendarTime {
  private static final long ABS_YEARS = 292277022400L, INTERNAL_TO_ABS = 9223371966606163200L;
  private final long seconds;
  private final int nano;

  private ExcelCalendarTime(long seconds, int nano) {
    this.seconds = seconds;
    this.nano = nano;
  }

  static long fromFloat(double n) {
    return (long) n;
  }

  static ExcelCalendarTime date(
      long year, long month, long day, long hour, long minute, long second, long nanosecond) {
    long m = month - 1;
    year += Math.floorDiv(m, 12);
    m = Math.floorMod(m, 12) + 1;
    second += Math.floorDiv(nanosecond, 1000000000);
    int nano = (int) Math.floorMod(nanosecond, 1000000000);
    minute += Math.floorDiv(second, 60);
    second = Math.floorMod(second, 60);
    hour += Math.floorDiv(minute, 60);
    minute = Math.floorMod(minute, 60);
    day += Math.floorDiv(hour, 24);
    hour = Math.floorMod(hour, 24);
    long jf = m < 3 ? 1 : 0,
        y = year - jf + ABS_YEARS,
        amonth = m + 12 * jf,
        ayday = (979 * amonth - 2919) >>> 5,
        century = Long.divideUnsigned(y, 100),
        cyear = Long.remainderUnsigned(y, 100),
        cday = 1461 * cyear / 4,
        centuryDays = Long.divideUnsigned(146097 * century, 4);
    long absDays = centuryDays + cday + ayday + day - 1;
    return new ExcelCalendarTime(
        absDays * 86400 + hour * 3600 + minute * 60 + second - INTERNAL_TO_ABS, nano);
  }

  private long absSeconds() {
    return seconds + INTERNAL_TO_ABS;
  }

  private long[] parts() {
    long days = Long.divideUnsigned(absSeconds(), 86400),
        d = 4 * days + 3,
        century = d / 146097,
        cd = d % 146097 | 3,
        product = 2939745 * cd,
        cyear = product >>> 32,
        ayday = (product & 0xffffffffL) / 2939745 / 4,
        m = 2141 * ayday + 197913,
        jf = ayday >= 306 ? 1 : 0;
    return new long[] {
      century * 100 - ABS_YEARS + cyear + jf, (m >>> 16) - 12 * jf, 1 + (m & 0xffff) / 2141
    };
  }

  long getYear() {
    return parts()[0];
  }

  int getMonthValue() {
    return (int) parts()[1];
  }

  int getDayOfMonth() {
    return (int) parts()[2];
  }

  int weekday() {
    return (int) ((Long.divideUnsigned(absSeconds(), 86400) + 3) % 7);
  }

  int getHour() {
    return (int) (Long.remainderUnsigned(absSeconds(), 86400) / 3600);
  }

  int getMinute() {
    return (int) (Long.remainderUnsigned(absSeconds(), 3600) / 60);
  }

  int getSecond() {
    return (int) Long.remainderUnsigned(absSeconds(), 60);
  }

  int getNano() {
    return nano;
  }

  ExcelCalendarTime plusSeconds(long n) {
    long sum = seconds + n; // Go Time.Add saturates when internal seconds overflow.
    if (n > 0 && sum < seconds) sum = Long.MAX_VALUE;
    else if (n < 0 && sum > seconds) sum = Long.MIN_VALUE;
    return new ExcelCalendarTime(sum, nano);
  }

  ExcelCalendarTime withNano(int n) {
    return new ExcelCalendarTime(seconds, n);
  }

  ExcelCalendarTime plusNanos(long ns) {
    long n = (long) nano + ns % 1000000000, sec = ns / 1000000000;
    if (n >= 1000000000) {
      n -= 1000000000;
      sec++;
    }
    if (n < 0) {
      n += 1000000000;
      sec--;
    }
    return plusSeconds(sec).withNano((int) n);
  }

  boolean isAfter(ExcelCalendarTime other) {
    return seconds > other.seconds || seconds == other.seconds && nano > other.nano;
  }

  double elapsed(long unit) {
    var base = date(1899, 12, 30, 0, 0, 0, 0);
    BigInteger ns =
        BigInteger.valueOf(seconds)
            .subtract(BigInteger.valueOf(base.seconds))
            .multiply(BigInteger.valueOf(1000000000))
            .add(BigInteger.valueOf(nano));
    long duration =
        ns.max(BigInteger.valueOf(Long.MIN_VALUE))
            .min(BigInteger.valueOf(Long.MAX_VALUE))
            .longValue();
    return (double) (duration / 1000000000) / (unit / 1000000000d)
        + (double) (duration % 1000000000) / (double) unit;
  }
}
