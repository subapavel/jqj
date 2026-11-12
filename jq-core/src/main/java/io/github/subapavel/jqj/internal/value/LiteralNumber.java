package io.github.subapavel.jqj.internal.value;

import static java.nio.charset.StandardCharsets.US_ASCII;

import io.github.subapavel.jqj.value.JqNumber;
import java.math.BigDecimal;

/**
 * A literal number written in a filter (ADR-0012, ADR-0028, ADR-0031; GLOSSARY "Literal number"): its canonical form,
 * its double and whether it fits a long, all computed once, when it is made. Public only so the kind can permit it.
 */
public final class LiteralNumber implements JqNumber {
  /** {@link Long#MAX_VALUE}'s digits: a 19-digit integer fits a long when it is at most these. */
  private static final String LONG_MAX = Long.toString(Long.MAX_VALUE);

  /** The normalised lexeme: a strict token of ADR-0012's grammar without a sign. */
  private final byte[] token;

  /** The canonical form's ASCII bytes. */
  private final byte[] canonical;

  private final double doubleValue;
  private final boolean fitsLong;

  private LiteralNumber(String token) {
    this.token = token.getBytes(US_ASCII);
    StringBuilder text = new StringBuilder();
    CanonicalForm.write(
        this.token,
        0,
        this.token.length,
        new CanonicalForm.Out() {
          @Override
          public void put(byte[] src, int off, int len) {
            text.append(new String(src, off, len, US_ASCII));
          }

          @Override
          public void put(char c) {
            text.append(c);
          }
        });
    this.canonical = text.toString().getBytes(US_ASCII);
    this.doubleValue = CanonicalForm.toDouble(this.token, 0, this.token.length);
    // ADR-0012 FITS_LONG of the normalised lexeme (ADR-0031): an integer token, which here has no sign, in the long
    // range. Two digit strings of the same length compare as their values.
    this.fitsLong =
        token.chars().allMatch(c -> c >= '0' && c <= '9')
            && (token.length() < LONG_MAX.length() || token.length() == LONG_MAX.length() && token.compareTo(LONG_MAX) <= 0);
  }

  /**
   * The literal of the filter lexer's number lexeme {@code ([0-9]+(\.[0-9]*)?|\.[0-9]+)([eE][+-]?[0-9]+)?}. The lexeme
   * is normalised to a strict token first, as jq's decNumber reads it: the integer part loses its leading zeros (an
   * all-zero or missing one becomes {@code 0}) and a point with no digit after it goes ({@code 007} is {@code 7},
   * {@code .5} is {@code 0.5}, {@code 1.e2} is {@code 1e2}).
   */
  public static LiteralNumber ofLexeme(String lexeme) {
    int point = lexeme.indexOf('.');
    int exponent = Math.max(lexeme.indexOf('e'), lexeme.indexOf('E'));
    int integerEnd = point >= 0 ? point : exponent >= 0 ? exponent : lexeme.length();
    int fractionEnd = exponent >= 0 ? exponent : lexeme.length();
    int digit = 0;
    while (digit < integerEnd - 1 && lexeme.charAt(digit) == '0') {
      digit++;
    }
    StringBuilder token = new StringBuilder(lexeme.length() + 1);
    token.append(integerEnd == 0 ? "0" : lexeme.substring(digit, integerEnd));
    if (point >= 0 && fractionEnd > point + 1) {
      token.append(lexeme, point, fractionEnd);
    }
    token.append(lexeme, fractionEnd, lexeme.length());
    return new LiteralNumber(token.toString());
  }

  /** The canonical form's ASCII bytes, which the writer copies; never written. */
  public byte[] canonical() {
    return canonical;
  }

  /** jq's compact text: the canonical form (ADR-0012). */
  @Override
  public String toJson() {
    return new String(canonical, US_ASCII);
  }

  /** ADR-0012 "Values", ADR-0028: an Infinity literal gives an infinity. */
  @Override
  public double doubleValue() {
    return doubleValue;
  }

  @Override
  public boolean fitsLong() {
    return fitsLong;
  }

  @Override
  public long longValue() {
    if (!fitsLong) {
      throw new ArithmeticException("the number is not an integer that a long holds");
    }
    return Long.parseLong(new String(token, US_ASCII));
  }

  /** ADR-0028: the canonical form's value, its scale kept; an Infinity literal throws {@code ArithmeticException}. */
  @Override
  public BigDecimal decimalValue() {
    return CanonicalForm.toDecimal(token, 0, token.length);
  }
}
