package io.github.subapavel.jqj;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.value.JqNull;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.nio.channels.Channels;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * GLOSSARY "Stub", ADR-0020's Survey rule and ADR-0024: every member that compiles, reads, runs or
 * writes ends with a JqCompileException whose message starts with the Stub mark. Only the mark is
 * checked, never the text after it.
 */
class StubTest {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String MARK = "jqj stub: ";

  @TestFactory
  Stream<DynamicTest> everyStubEndsInAJqCompileException() throws ReflectiveOperationException {
    JqEngine engine = JqEngine.builder().build();
    Workspace workspace = engine.newWorkspace();
    JqFilter filter = behindTheStub(JqFilter.class);
    JsonDoc doc = behindTheStub(JsonDoc.class);

    Map<String, ThrowingCallable> stubs = new LinkedHashMap<>();
    stubs.put("JqEngine.compile(String)", () -> engine.compile("."));
    stubs.put(
        "JqFilter.run(JqValue, Bindings, JqSink, Workspace)",
        () -> filter.run(JqNull.NULL, Bindings.builder().build(), v -> true, workspace));
    stubs.put("Workspace.read(MemorySegment)", () -> workspace.read(MemorySegment.ofArray("{\"a\":1}".getBytes(UTF_8))));
    stubs.put("JsonDoc.root()", doc::root);
    stubs.put("JsonDoc.close()", doc::close);
    stubs.put(
        "JsonWriter.sink(Workspace, WritableByteChannel, OutputOptions)",
        () -> JsonWriter.sink(workspace, Channels.newChannel(new ByteArrayOutputStream()), OutputOptions.compact()));
    stubs.put(
        "JsonWriter.sink(Workspace, OutputStream, OutputOptions)",
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

  /**
   * While compile and read are Stubs no JqFilter or JsonDoc can be obtained through the API, so one is
   * made through its constructor with zero and null arguments.
   */
  private static <T> T behindTheStub(Class<T> type) throws ReflectiveOperationException {
    Constructor<?> constructor =
        Arrays.stream(type.getDeclaredConstructors())
            .min(Comparator.comparingInt(Constructor::getParameterCount))
            .orElseThrow(() -> new AssertionError("no constructor in " + type.getName()));
    constructor.setAccessible(true);
    Object[] args = Arrays.stream(constructor.getParameterTypes()).map(StubTest::zero).toArray();
    return type.cast(constructor.newInstance(args));
  }

  /** The default value of a parameter type: 0 or false for a primitive, null otherwise. */
  private static Object zero(Class<?> type) {
    return Array.get(Array.newInstance(type, 1), 0);
  }
}
