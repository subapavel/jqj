package io.github.subapavel.jqj.internal.value;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.value.JqString;
import java.lang.foreign.MemorySegment;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * A string built at run time (#34 design section 7): {@code BuiltString.of(String)} is a {@link JqString} whose
 * {@code value()} is the text and whose {@code utf8()} is a read-only slice of its UTF-8 bytes. {@code toJson()} is
 * a Stub until #35.
 */
class BuiltStringTest {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String MARK = "jqj stub: ";

  @Test
  void valueAndUtf8RoundTrip() {
    // One char of each UTF-8 length: U+0041, U+00E9, U+20AC, U+1D11E (a surrogate pair in Java), with SP and a quote.
    String text = "A é€𝄞\"";
    JqString s = BuiltString.of(text);

    assertThat(s.value()).as("value()").isEqualTo(text);
    MemorySegment utf8 = s.utf8();
    // The bytes, written out: 41 20, C3 A9, E2 82 AC, F0 9D 84 9E, 22.
    assertThat(HexFormat.ofDelimiter(" ").formatHex(utf8.toArray(JAVA_BYTE))).as("utf8() bytes").isEqualTo("41 20 c3 a9 e2 82 ac f0 9d 84 9e 22");
    assertThat(utf8.isReadOnly()).as("utf8() is read-only").isTrue();

    // jq's error texts are ASCII: the one #34's Field builds reads back whole.
    String error = "Cannot index array with string (\"abcdefghijklmnopqrstuvwx...\")";
    assertThat(BuiltString.of(error).value()).as("value() of an error text").isEqualTo(error);
    assertThat(BuiltString.of(error).utf8().byteSize()).as("utf8() length of an error text").isEqualTo(62);

    // The empty string: no bytes.
    assertThat(BuiltString.of("").value()).as("value() of the empty string").isEmpty();
    assertThat(BuiltString.of("").utf8().byteSize()).as("utf8() length of the empty string").isZero();
  }

  @Test
  void toJsonIsAStub() {
    // #34 design section 7: toJson() and its Printer arm are #35's (jq's re-escape).
    assertThatExceptionOfType(JqCompileException.class)
        .as("toJson() of a built string")
        .isThrownBy(() -> BuiltString.of("a").toJson())
        .withMessageStartingWith(MARK);
  }
}
