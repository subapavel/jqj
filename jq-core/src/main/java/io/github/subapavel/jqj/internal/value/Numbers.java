package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqNumber;

/**
 * The number rules every operator shares (ADR-0012): the one three-way compare, jq's {@code jvp_number_cmp}, which
 * Equality and Order use (ADR-0037, design approval O5), and jq's {@code jv_number_negate}.
 */
public final class Numbers {
  private Numbers() {}

  /**
   * jq's {@code jvp_number_cmp} (ADR-0012 "Comparison"): -1, 0 or 1. Two literals compare as exact decimals: by long
   * when both fit one, by double when both are short, otherwise exactly ({@link CanonicalForm#compare}); any other
   * pair as doubles, with primitive {@code <} and {@code ==}, so {@code -0} equals {@code 0} and a NaN on either side
   * gives 1, as in jq. It charges nothing: Equality's compare (ADR-0034 amendment for #65).
   */
  public static int compare(JqNumber a, JqNumber b) {
    return compare(null, a, b);
  }

  /**
   * {@link #compare(JqNumber, JqNumber)} for Order (ADR-0037): the exact path reads both tokens whole, so there it first
   * charges {@code state}, unless it is null, one tick per started KiB of each token; the long and double paths read a
   * fixed number of bytes and charge nothing.
   */
  static int compare(RunState state, JqNumber a, JqNumber b) {
    if (a instanceof Literal x && b instanceof Literal y) {
      if (a.fitsLong() && b.fitsLong()) {
        return Long.compare(a.longValue(), b.longValue());
      }
      if (!x.isShort() || !y.isShort()) {
        int xoff = x.tokenOffset();
        int yoff = y.tokenOffset();
        if (state != null) {
          state.tick(kib(x.tokenLength()) + kib(y.tokenLength()));
        }
        return CanonicalForm.compare(x.tokenBytes(), xoff, x.tokenLength(), y.tokenBytes(), yoff, y.tokenLength());
      }
    }
    double x = a.doubleValue();
    double y = b.doubleValue();
    return x < y ? -1 : x == y ? 0 : 1;
  }

  /** The KiB that {@code bytes} bytes start: 0 for none. */
  private static int kib(int bytes) {
    return (bytes + 1023) >>> 10;
  }

  /**
   * jq's {@code jv_number_negate}: a literal's negation is a literal (ADR-0012, {@link LiteralNumber#negated}), a
   * computed number's is its double negated.
   */
  public static JqNumber negate(JqNumber n) {
    return switch (n) {
      case DoubleNumber d -> new DoubleNumber(-d.doubleValue());
      case Literal l -> l.negate();
    };
  }
}
