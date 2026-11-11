package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqValue;

/**
 * An array of a document (ADR-0003, ADR-0016): its document and tape index. Public only so the kind can permit it.
 * Equality is the record's, never jq's (ADR-0026).
 */
public record ArrayView(Document doc, int index) implements JqArray {

  @Override
  public String toJson() {
    throw Stub.unbuilt("JqArray.toJson");
  }

  @Override
  public int length() {
    long[] tape = doc.live();
    int end = Tape.end(tape, index);
    int length = 0;
    for (int i = index + 1; i < end; i = Tape.next(tape, i)) {
      length++;
    }
    return length;
  }

  /** @throws IndexOutOfBoundsException unless {@code 0 <= i < length()} (ADR-0026) */
  @Override
  public JqValue get(int i) {
    long[] tape = doc.live();
    int end = Tape.end(tape, index);
    int n = 0;
    for (int at = index + 1; at < end; at = Tape.next(tape, at)) {
      if (n == i) {
        return doc.valueAt(tape, at);
      }
      n++;
    }
    throw new IndexOutOfBoundsException("Index " + i + " out of bounds for length " + n);
  }
}
