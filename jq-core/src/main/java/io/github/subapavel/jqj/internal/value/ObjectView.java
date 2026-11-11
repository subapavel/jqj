package io.github.subapavel.jqj.internal.value;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqValue;
import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * An object of a document (ADR-0003, ADR-0016): its document and tape index. Public only so the kind can permit it.
 * Its entries alternate key and value. The tape keeps duplicate keys, so every member checks them again, as jq does:
 * a key keeps its first position and its last value (ADR-0002). That check compares each key with the earlier ones,
 * so {@link #size()} and {@link #forEach} take time quadratic in the number of keys.
 */
public record ObjectView(Document doc, int index) implements JqObject {

  @Override
  public String toJson() {
    throw Stub.unbuilt("JqObject.toJson");
  }

  @Override
  public int size() {
    long[] tape = doc.live();
    byte[] bytes = doc.bytes;
    int end = keysEnd(tape);
    int size = 0;
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      if (firstOf(tape, bytes, k) == k) {
        size++;
      }
    }
    return size;
  }

  /** The value of the last {@code key}, or Java {@code null} when the object has no such key (ADR-0026). */
  @Override
  public JqValue get(String key) {
    long[] tape = doc.live();
    byte[] bytes = doc.bytes;
    int end = keysEnd(tape);
    byte[] wanted = key.getBytes(UTF_8);
    int value = 0; // none yet: index 0 is the root, never a member's value
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      long entry = tape[k];
      int from = Tape.off(entry) + 1;
      if (Arrays.equals(bytes, from, from + Tape.span(entry) - 2, wanted, 0, wanted.length)) {
        value = k + 1;
      }
    }
    return value == 0 ? null : doc.valueAt(tape, value);
  }

  @Override
  public void forEach(BiConsumer<? super String, ? super JqValue> action) {
    long[] tape = doc.live();
    byte[] bytes = doc.bytes;
    int end = keysEnd(tape);
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      if (firstOf(tape, bytes, k) == k) {
        action.accept(StringView.text(bytes, tape[k]), doc.valueAt(tape, lastValueOf(tape, bytes, k, end)));
      }
    }
  }

  /** The end of this object's entries, once every key is known to be a tag-5 string. */
  private int keysEnd(long[] tape) {
    int end = Tape.end(tape, index);
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      StringView.requirePlain(tape[k]);
    }
    return end;
  }

  /** The first key equal to the key at {@code k}, which is {@code k} itself when no earlier key is. */
  private int firstOf(long[] tape, byte[] bytes, int k) {
    int first = index + 1;
    while (!sameKey(tape, bytes, first, k)) {
      first = Tape.next(tape, first + 1);
    }
    return first;
  }

  /** The value of the last key equal to the key at {@code k}. */
  private static int lastValueOf(long[] tape, byte[] bytes, int k, int end) {
    int value = k + 1;
    for (int later = Tape.next(tape, k + 1); later < end; later = Tape.next(tape, later + 1)) {
      if (sameKey(tape, bytes, later, k)) {
        value = later + 1;
      }
    }
    return value;
  }

  /** Two tag-5 keys are equal when their bytes are, quotes included. */
  private static boolean sameKey(long[] tape, byte[] bytes, int a, int b) {
    int from = Tape.off(tape[a]);
    int other = Tape.off(tape[b]);
    return Arrays.equals(bytes, from, from + Tape.span(tape[a]), bytes, other, other + Tape.span(tape[b]));
  }
}
