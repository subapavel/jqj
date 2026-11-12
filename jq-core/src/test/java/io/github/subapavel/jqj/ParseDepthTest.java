package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code JqEngine.compile} on a deeply nested filter (#81): it ends with jq's {@code memory exhausted} compile error,
 * never with a {@code StackOverflowError}. jq-ref 1.8.2's first rejected depths, recorded in diff/depth.rec: 9995
 * parentheses or brackets around 1, 3332 objects {@code {"a": ...}} around 1, each rc 3 with one {@code memory
 * exhausted} block.
 */
class ParseDepthTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  /** {@code depth} copies of {@code open}, then {@code 1}, then {@code depth} copies of {@code close}. */
  private static String nested(String open, String close, int depth) {
    return open.repeat(depth) + "1" + close.repeat(depth);
  }

  static Stream<Arguments> jqsFirstRejectedDepths() {
    return Stream.of(
        Arguments.argumentSet("9995 parentheses", "(", ")", 9995),
        Arguments.argumentSet("9995 brackets", "[", "]", 9995),
        Arguments.argumentSet("3332 objects", "{\"a\":", "}", 3332));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("jqsFirstRejectedDepths")
  void jqsFirstRejectedDepthIsMemoryExhaustedNotAStackOverflow(String open, String close, int depth) {
    JqCompileException e =
        assertThatExceptionOfType(JqCompileException.class).isThrownBy(() -> ENGINE.compile(nested(open, close, depth))).actual();

    assertThat(e.blocks()).as("blocks").hasSize(1);
    assertThat(e.blocks().getFirst()).as("block").startsWith("jq: error: memory exhausted at <top-level>, line 1, column ");
    assertThat(e.getMessage()).as("message").endsWith("\njq: 1 compile error");
  }
}
