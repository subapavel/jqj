package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.internal.json.Printer;
import io.github.subapavel.jqj.value.JqNumber;
import java.math.BigDecimal;

/**
 * A number of a document (ADR-0003, ADR-0012, ADR-0016): its document and tape index. Public only so the kind can
 * permit it. Its double and decimal values are #36's.
 */
public record NumberView(Document doc, int index) implements JqNumber {

  /** jq's compact text (ADR-0012): a CANONICAL token's bytes; any other token is a Stub until #36. */
  @Override
  public String toJson() {
    return Printer.json(doc, index);
  }

  @Override
  public double doubleValue() {
    throw Stub.unbuilt("JqNumber.doubleValue");
  }

  @Override
  public boolean fitsLong() {
    return (Tape.span(doc.live()[index]) & Tape.FITS_LONG) != 0;
  }

  @Override
  public long longValue() {
    long[] tape = doc.live();
    int span = Tape.span(tape[index]);
    if ((span & Tape.FITS_LONG) == 0) {
      throw new ArithmeticException("the number is not an integer that a long holds");
    }
    byte[] bytes = doc.bytes;
    int p = Tape.off(tape[index]);
    int end = p + (span & Tape.NUMBER_LENGTH);
    boolean negative = bytes[p] == '-';
    if (negative) {
      p++;
    }
    // Accumulated below zero, so the smallest long needs no special case; FITS_LONG rules out an overflow.
    long value = 0;
    for (; p < end; p++) {
      value = value * 10 - (bytes[p] - '0');
    }
    return negative ? value : -value;
  }

  @Override
  public BigDecimal decimalValue() {
    throw Stub.unbuilt("JqNumber.decimalValue");
  }
}
