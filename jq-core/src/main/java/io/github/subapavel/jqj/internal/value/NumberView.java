package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.value.JqNumber;
import java.math.BigDecimal;

/** A number of a document (ADR-0016). Public only so the kind can permit it; every method is a Stub. */
public final class NumberView implements JqNumber {
  NumberView() {}

  @Override
  public String toJson() {
    throw Stub.unbuilt("JqNumber.toJson");
  }

  @Override
  public double doubleValue() {
    throw Stub.unbuilt("JqNumber.doubleValue");
  }

  @Override
  public boolean fitsLong() {
    throw Stub.unbuilt("JqNumber.fitsLong");
  }

  @Override
  public long longValue() {
    throw Stub.unbuilt("JqNumber.longValue");
  }

  @Override
  public BigDecimal decimalValue() {
    throw Stub.unbuilt("JqNumber.decimalValue");
  }
}
