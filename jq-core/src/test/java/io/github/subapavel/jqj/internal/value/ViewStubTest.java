package io.github.subapavel.jqj.internal.value;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.Workspace;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * ADR-0024: the view members that are still Stubs after #32, each ending in a JqCompileException whose message
 * starts with the Stub mark. The views come from {@code Workspace.read} (#32 conflict C4). #32 built the other
 * accessors, so their entries ended with their Stubs. What is left ends with its ticket: the four
 * {@code toJson()} with the S1.11 writer, {@code doubleValue} and {@code decimalValue} with S1.14, and every
 * member that must decode a tag-6 string (an escape, DEL or a byte from 0x80; #32 conflict C6) with S1.13.
 */
class ViewStubTest {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String MARK = "jqj stub: ";

  /**
   * One array, so that no object lookup meets a tag-6 key before its own entry: a number with a fraction, a
   * plain string, an empty array and object, three tag-6 strings (an escape, non-ASCII, DEL), an object whose
   * first key is the tag-6 spelling of its second key, and one whose second key is the tag-6 spelling of its first.
   */
  private static final String DOC =
      "[1.5,\"x\",[],{},\"a\\nb\",\"\u00e9\",\"\u007f\",{\"\\u0061\":1,\"a\":2},{\"a\":1,\"\\u0061\":2}]";

  @TestFactory
  Stream<DynamicTest> viewMembersNotBuiltYetAreStubs() {
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    JqArray root = view(JqArray.class, ArrayView.class, workspace.read(MemorySegment.ofArray(DOC.getBytes(UTF_8))).root());
    JqNumber number = view(JqNumber.class, NumberView.class, root.get(0));
    JqString plain = view(JqString.class, StringView.class, root.get(1));
    JqArray array = view(JqArray.class, ArrayView.class, root.get(2));
    JqObject object = view(JqObject.class, ObjectView.class, root.get(3));
    JqString escaped = view(JqString.class, StringView.class, root.get(4));
    JqString nonAscii = view(JqString.class, StringView.class, root.get(5));
    JqString del = view(JqString.class, StringView.class, root.get(6));
    JqObject tag6Key = view(JqObject.class, ObjectView.class, root.get(7));
    JqObject tag6KeyLater = view(JqObject.class, ObjectView.class, root.get(8));

    Map<String, ThrowingCallable> stubs = new LinkedHashMap<>();
    stubs.put("NumberView.toJson()", number::toJson);
    stubs.put("NumberView.doubleValue()", number::doubleValue);
    stubs.put("NumberView.decimalValue()", number::decimalValue);
    stubs.put("StringView.toJson()", plain::toJson);
    stubs.put("ArrayView.toJson()", array::toJson);
    stubs.put("ObjectView.toJson()", object::toJson);
    stubs.put("StringView.value() of an escape", escaped::value);
    stubs.put("StringView.utf8() of an escape", escaped::utf8);
    stubs.put("StringView.value() of non-ASCII", nonAscii::value);
    stubs.put("StringView.utf8() of non-ASCII", nonAscii::utf8);
    stubs.put("StringView.value() of DEL", del::value);
    stubs.put("StringView.utf8() of DEL", del::utf8);
    stubs.put("ObjectView.size() meeting a tag-6 key", tag6Key::size);
    stubs.put("ObjectView.get(String) meeting a tag-6 key", () -> tag6Key.get("a"));
    stubs.put("ObjectView.forEach(BiConsumer) meeting a tag-6 key", () -> tag6Key.forEach((k, v) -> {}));

    Stream<DynamicTest> marked =
        stubs.entrySet().stream()
            .map(
                stub ->
                    dynamicTest(
                        stub.getKey(),
                        () ->
                            assertThatThrownBy(stub.getValue())
                                .isInstanceOf(JqCompileException.class)
                                .hasMessageStartingWith(MARK)));
    // ADR-0024 (#22 story 28): a Stub never looks like a wrong result. jq-ref 1.8.2, observed 2026-11-11:
    //   printf -- '{"a":1,"\\u0061":2}' | jq-ref -c '., keys, length, .a'  ->  {"a":2}  ["a"]  1  2   (rc 0)
    // so a call of a -> 1 before the Stub would hand the host a wrong value.
    Stream<DynamicTest> noCall =
        Stream.of(
            dynamicTest(
                "ObjectView.forEach(BiConsumer) meeting a tag-6 key after a plain one calls nothing",
                () -> {
                  List<String> calls = new ArrayList<>();
                  assertThatThrownBy(() -> tag6KeyLater.forEach((k, v) -> calls.add(k + " -> " + v)))
                      .isInstanceOf(JqCompileException.class)
                      .hasMessageStartingWith(MARK);
                  assertThat(calls).as("calls made before the Stub").isEmpty();
                }));
    return Stream.concat(marked, noCall);
  }

  /** Asserts that {@code value} is the view class {@code view} of the kind {@code kind}, and returns it. */
  private static <T extends JqValue> T view(Class<T> kind, Class<? extends T> view, JqValue value) {
    assertThat(value).isInstanceOf(view);
    return kind.cast(value);
  }
}
