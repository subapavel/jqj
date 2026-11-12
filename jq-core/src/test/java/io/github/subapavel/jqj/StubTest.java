package io.github.subapavel.jqj;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.value.JqValue;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.nio.channels.Channels;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * GLOSSARY "Stub", ADR-0020's Survey rule and ADR-0024: every member that compiles, runs or writes ends
 * with a JqCompileException whose message starts with the Stub mark, and the malformed-input Stub is a
 * JqSyntaxException with the same mark. Only the mark is checked, never the text after it. #32 built
 * {@code Workspace.read}, {@code JsonDoc.root()} and {@code JsonDoc.close()}, so their entries ended with
 * their Stubs (#32 conflict C4). #33 built {@code compile(".")}, {@code JqFilter.run} and the compact
 * {@code JsonWriter.sink}, so those entries ended with their Stubs too (#33 design C12): compile stays a Stub
 * for any other text; {@code CompileTest} and {@code JsonWriterTest} test what was built. #37 built the sink for
 * {@code pretty()} on both overloads, which {@link #sinkMembersBuiltBy37} checks instead.
 */
class StubTest {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String MARK = "jqj stub: ";

  @TestFactory
  Stream<DynamicTest> everyStubEndsInAJqCompileException() {
    JqEngine engine = JqEngine.builder().build();

    Map<String, ThrowingCallable> stubs = new LinkedHashMap<>();
    stubs.put("JqEngine.compile(String) of an unbuilt construct (the literal 1)", () -> engine.compile("1"));

    Stream<DynamicTest> mark = Stream.of(dynamicTest("Stub.PREFIX is the mark", () -> assertThat(Stub.PREFIX).isEqualTo(MARK)));
    Stream<DynamicTest> members =
        stubs.entrySet().stream()
            .map(
                stub ->
                    dynamicTest(
                        stub.getKey(),
                        () ->
                            assertThatThrownBy(stub.getValue())
                                .isInstanceOf(JqCompileException.class)
                                .hasMessageStartingWith(MARK)));
    return Stream.concat(mark, members);
  }

  @TestFactory
  Stream<DynamicTest> sinkMembersBuiltBy37() {
    // #37 built the pretty() sink, so its two entries left the Stub list (#33 design C12). pretty.rec pretty/no-filter,
    // jq-ref 1.8.2: {"a":[1,{"b":null}]} prints as below without -c.
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    String recorded = "{\n  \"a\": [\n    1,\n    {\n      \"b\": null\n    }\n  ]\n}\n";
    Map<String, ThrowingCallable> built = new LinkedHashMap<>();
    ByteArrayOutputStream behindChannel = new ByteArrayOutputStream();
    built.put(
        "JsonWriter.sink(Workspace, WritableByteChannel, OutputOptions) pretty",
        () -> {
          JsonWriter.sink(workspace, Channels.newChannel(behindChannel), OutputOptions.pretty()).emit(root(workspace));
          assertThat(behindChannel.toString(UTF_8)).isEqualTo(recorded);
        });
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    built.put(
        "JsonWriter.sink(Workspace, OutputStream, OutputOptions) pretty",
        () -> {
          JsonWriter.sink(workspace, stream, OutputOptions.pretty()).emit(root(workspace));
          assertThat(stream.toString(UTF_8)).isEqualTo(recorded);
        });
    return built.entrySet().stream().map(entry -> dynamicTest(entry.getKey(), () -> entry.getValue().call()));
  }

  private static JqValue root(Workspace workspace) {
    return workspace.read(MemorySegment.ofArray("{\"a\":[1,{\"b\":null}]}\n".getBytes(UTF_8))).root();
  }

  @Test
  void malformedInputStubIsAJqSyntaxExceptionWithTheMark() {
    // ADR-0024: "The malformed-input Stub is a JqSyntaxException with the same prefix". jq's text by replay is
    // spec 5's, which ends this test with its Stub.
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    assertThatThrownBy(() -> workspace.read(MemorySegment.ofArray("{\"a\":".getBytes(UTF_8))))
        .isInstanceOf(JqSyntaxException.class)
        .hasMessageStartingWith(MARK);
  }
}
