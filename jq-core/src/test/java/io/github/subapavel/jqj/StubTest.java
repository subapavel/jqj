package io.github.subapavel.jqj;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.subapavel.jqj.internal.Stub;
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
 * for any other text and the sink for {@code pretty()} on both overloads; {@code CompileTest} and
 * {@code JsonWriterTest} test what was built.
 */
class StubTest {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String MARK = "jqj stub: ";

  @TestFactory
  Stream<DynamicTest> everyStubEndsInAJqCompileException() {
    JqEngine engine = JqEngine.builder().build();
    Workspace workspace = engine.newWorkspace();

    Map<String, ThrowingCallable> stubs = new LinkedHashMap<>();
    stubs.put("JqEngine.compile(String) of an unbuilt construct (the literal 1)", () -> engine.compile("1"));
    stubs.put(
        "JsonWriter.sink(Workspace, WritableByteChannel, OutputOptions) pretty",
        () -> JsonWriter.sink(workspace, Channels.newChannel(new ByteArrayOutputStream()), OutputOptions.pretty()));
    stubs.put(
        "JsonWriter.sink(Workspace, OutputStream, OutputOptions) pretty",
        () -> JsonWriter.sink(workspace, new ByteArrayOutputStream(), OutputOptions.pretty()));

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
