// Portions ported from Excelize v2.11.0; BSD 3-Clause.
// Copyright (c) 2016-2026 The excelize Authors; (c) 2011-2017 Geoffrey J. Teale.
// See META-INF/licenses/EXCELIZE-LICENSE.txt for the complete notice and disclaimer.
package com.tencent.weknora.sourceloc;

import java.math.*;
import java.util.*;

final class OfficeNumberFormat {
  static String goPrecision15(double value) {
    BigDecimal rounded =
        new BigDecimal(value, new MathContext(15, RoundingMode.HALF_EVEN)).stripTrailingZeros();
    int exponent = rounded.precision() - rounded.scale() - 1;
    if (value != 0 && (exponent >= 15 || exponent < -4))
      return rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString()
          + String.format(Locale.ROOT, "E%+03d", exponent);
    return rounded.toPlainString();
  }

  static String canonical(String value) {
    String lower = value.toLowerCase(Locale.ROOT);
    if (lower.equals("nan")) return "NaN";
    if (Set.of("inf", "+inf", "infinity", "+infinity").contains(lower)) return "+Inf";
    if (Set.of("-inf", "-infinity").contains(lower)) return "-Inf";
    if (value.contains("_")
        || !(value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
            || value.matches("[+-]?0[xX][0-9a-fA-F.]+[pP][+-]?[0-9]+"))) return value;
    try {
      double number = Double.parseDouble(value);
      if (!Double.isFinite(number)) return value;
      if (number == 0 && Double.doubleToRawLongBits(number) < 0) return "-0";
      String plain = BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
      int precision = plain.length() - (plain.contains(".") ? 1 : 0);
      return precision > 15 ? goPrecision15(number) : plain;
    } catch (NumberFormatException invalid) {
      return value;
    }
  }
}
