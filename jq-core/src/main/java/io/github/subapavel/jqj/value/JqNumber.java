package io.github.subapavel.jqj.value;

import io.github.subapavel.jqj.internal.value.NumberView;
import java.math.BigDecimal;

/** A jq number (ADR-0016, ADR-0012). */
public sealed interface JqNumber extends JqValue permits NumberView {
  /** The number as a double, by ADR-0012's 17-digit rule. */
  double doubleValue();

  /** Whether the number is an integer a long holds; false for {@code -0}. */
  boolean fitsLong();

  /**
   * The number as a long.
   *
   * @throws ArithmeticException unless {@link #fitsLong()}
   */
  long longValue();

  /**
   * The number exactly for a literal, by its printed digits for a computed one.
   *
   * @throws ArithmeticException for NaN and the infinities
   */
  BigDecimal decimalValue();
}
