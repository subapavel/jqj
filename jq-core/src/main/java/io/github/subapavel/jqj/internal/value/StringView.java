package io.github.subapavel.jqj.internal.value;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.internal.json.Printer;
import io.github.subapavel.jqj.value.JqString;
import java.lang.foreign.MemorySegment;

/**
 * A string of a document (ADR-0003, ADR-0016): its document and tape index. Public only so the kind can permit it.
 * A tag-6 string must be decoded, which is #35's; until then its members are Stubs.
 */
public record StringView(Document doc, int index) implements JqString {

  /** jq's compact text (ADR-0015). */
  @Override
  public String toJson() {
    return Printer.json(doc, index);
  }

  @Override
  public String value() {
    return text(doc.bytes, plain(doc.live()));
  }

  /** A read-only slice of the document's bytes, without a copy (ADR-0016). */
  @Override
  public MemorySegment utf8() {
    long entry = plain(doc.live());
    return MemorySegment.ofArray(doc.bytes).asSlice(Tape.off(entry) + 1, Tape.span(entry) - 2).asReadOnly();
  }

  private long plain(long[] tape) {
    long entry = tape[index];
    requirePlain(entry);
    return entry;
  }

  /** The text of a tag-5 string entry: the bytes between its quotes. */
  static String text(byte[] bytes, long entry) {
    return new String(bytes, Tape.off(entry) + 1, Tape.span(entry) - 2, UTF_8);
  }

  /** Stops at a tag-6 string, an object key included: decoding it is #35's. */
  static void requirePlain(long entry) {
    if (Tape.tag(entry) != Tape.STRING) {
      throw Stub.unbuilt("string decoding");
    }
  }
}
