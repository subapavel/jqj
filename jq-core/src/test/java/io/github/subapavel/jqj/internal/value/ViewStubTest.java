package io.github.subapavel.jqj.internal.value;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * ADR-0024: the view members that are still Stubs after #32, each ending in a JqCompileException whose message
 * starts with the Stub mark. The views come from {@code Workspace.read} (#32 conflict C4). #32 built the other
 * accessors, so their entries ended with their Stubs. #33 (S1.11) built the four {@code toJson()} and the UTF-8
 * classification, under which non-ASCII well-formed UTF-8 is tag 5; those six entries ended with their Stubs and
 * {@link #viewMembersBuiltBy33} checks them instead (#33 design C12). What is left ends with its ticket:
 * {@code doubleValue} and {@code decimalValue} with #36, and every member that must decode a tag-6 string (an escape,
 * DEL or bytes that are not well-formed UTF-8; #33 design C5) with #35.
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

  /**
   * Each dynamic test reads {@link #DOC} itself, so the read runs inside the test: the tests that read a tag-6
   * string then cover the reader's classification too (PIT counts a test only for what runs inside it).
   */
  @TestFactory
  Stream<DynamicTest> viewMembersNotBuiltYetAreStubs() {
    Map<String, Function<JqArray, ThrowingCallable>> stubs = new LinkedHashMap<>();
    stubs.put("NumberView.doubleValue()", root -> number(root)::doubleValue);
    stubs.put("NumberView.decimalValue()", root -> number(root)::decimalValue);
    stubs.put("StringView.value() of an escape", root -> string(root, 4)::value);
    stubs.put("StringView.utf8() of an escape", root -> string(root, 4)::utf8);
    stubs.put("StringView.value() of DEL", root -> string(root, 6)::value);
    stubs.put("StringView.utf8() of DEL", root -> string(root, 6)::utf8);
    stubs.put("ObjectView.size() meeting a tag-6 key", root -> object(root, 7)::size);
    stubs.put(
        "ObjectView.get(String) meeting a tag-6 key",
        root -> {
          JqObject tag6Key = object(root, 7);
          return () -> tag6Key.get("a");
        });
    stubs.put(
        "ObjectView.forEach(BiConsumer) meeting a tag-6 key",
        root -> {
          JqObject tag6Key = object(root, 7);
          return () -> tag6Key.forEach((k, v) -> {});
        });

    Stream<DynamicTest> marked =
        stubs.entrySet().stream()
            .map(
                stub ->
                    dynamicTest(
                        stub.getKey(),
                        () ->
                            assertThatThrownBy(stub.getValue().apply(readDoc()))
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
                  JqObject tag6KeyLater = object(readDoc(), 8);
                  List<String> calls = new ArrayList<>();
                  assertThatThrownBy(() -> tag6KeyLater.forEach((k, v) -> calls.add(k + " -> " + v)))
                      .isInstanceOf(JqCompileException.class)
                      .hasMessageStartingWith(MARK);
                  assertThat(calls).as("calls made before the Stub").isEmpty();
                }));
    return Stream.concat(marked, noCall);
  }

  /**
   * The six entries #33 ended (design C12): the four {@code toJson()} are the compact writer's text, and a non-ASCII
   * string of well-formed UTF-8 is tag 5, so it reads. jq-ref 1.8.2, observed 2026-11-11:
   * {@code printf '[1.5,"x",[],{},"\xc3\xa9"]' | jq-ref -c .} -> {@code [1.5,"x",[],{},"é"]} (rc 0).
   */
  @TestFactory
  Stream<DynamicTest> viewMembersBuiltBy33() {
    Map<String, Function<JqArray, Object>> built = new LinkedHashMap<>();
    Map<String, Object> expected = new LinkedHashMap<>();
    built.put("NumberView.toJson()", root -> number(root).toJson());
    expected.put("NumberView.toJson()", "1.5");
    built.put("StringView.toJson()", root -> string(root, 1).toJson());
    expected.put("StringView.toJson()", "\"x\"");
    built.put("ArrayView.toJson()", root -> view(JqArray.class, ArrayView.class, root.get(2)).toJson());
    expected.put("ArrayView.toJson()", "[]");
    built.put("ObjectView.toJson()", root -> object(root, 3).toJson());
    expected.put("ObjectView.toJson()", "{}");
    built.put("StringView.value() of non-ASCII", root -> string(root, 5).value());
    expected.put("StringView.value() of non-ASCII", "é");
    built.put("StringView.utf8() of non-ASCII", root -> HexFormat.ofDelimiter(" ").formatHex(string(root, 5).utf8().toArray(JAVA_BYTE)));
    expected.put("StringView.utf8() of non-ASCII", "c3 a9");
    return built.entrySet().stream()
        .map(
            member ->
                dynamicTest(
                    member.getKey(),
                    () -> assertThat(member.getValue().apply(readDoc())).as(member.getKey()).isEqualTo(expected.get(member.getKey()))));
  }

  /** Reads {@link #DOC} into a new Workspace and returns its root array. */
  private static JqArray readDoc() {
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    return view(JqArray.class, ArrayView.class, workspace.read(MemorySegment.ofArray(DOC.getBytes(UTF_8))).root());
  }

  private static JqNumber number(JqArray root) {
    return view(JqNumber.class, NumberView.class, root.get(0));
  }

  private static JqString string(JqArray root, int index) {
    return view(JqString.class, StringView.class, root.get(index));
  }

  private static JqObject object(JqArray root, int index) {
    return view(JqObject.class, ObjectView.class, root.get(index));
  }

  /** Asserts that {@code value} is the view class {@code view} of the kind {@code kind}, and returns it. */
  private static <T extends JqValue> T view(Class<T> kind, Class<? extends T> view, JqValue value) {
    assertThat(value).isInstanceOf(view);
    return kind.cast(value);
  }
}
