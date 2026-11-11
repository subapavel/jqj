package io.github.subapavel.jqj.oracle.jqtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.JqFilterException;
import io.github.subapavel.jqj.JqSyntaxException;
import io.github.subapavel.jqj.JqTermination;
import io.github.subapavel.jqj.Limits;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The jqtest oracle's jqj over the public API (ADR-0020, ADR-0024), against the S1.02 Stubs. */
class ApiJqjTest {

  @Test
  void theLimitsAreEmbeddedWithoutTheDeadline() {
    Limits e = Limits.embedded();

    assertThat(ApiJqj.limits())
        .isEqualTo(
            new Limits(
                e.steps(), Limits.UNLIMITED_TIME, e.nestingDepth(), e.recursionDepth(), e.bytesBuilt(), e.bytesWritten(), e.regexMatchTime(), e.stackBytes()));
  }

  @Test
  void eachOfJqjsFourExceptionsKeepsItsKindAndACompileErrorItsBlocks() {
    JqjException compile = ApiJqj.translate(new JqCompileException("jq: error: x", 1, 2));
    assertThat(compile.kind()).isEqualTo(JqjException.Kind.COMPILE);
    assertThat(compile.getMessage()).isEqualTo("jq: error: x");
    assertThat(compile.blocks()).containsExactly("jq: error: x");

    JqjException syntax = ApiJqj.translate(new JqSyntaxException("Unfinished JSON term", 3, 1, 4));
    assertThat(syntax.kind()).isEqualTo(JqjException.Kind.SYNTAX);
    assertThat(syntax.getMessage()).isEqualTo("Unfinished JSON term");

    assertThat(ApiJqj.translate(new JqFilterException(JqNull.NULL)).kind()).isEqualTo(JqjException.Kind.FILTER);

    JqjException halt = ApiJqj.translate(new JqTermination(JqTermination.Cause.HALT, 5, JqNull.NULL, null));
    assertThat(halt.kind()).isEqualTo(JqjException.Kind.TERMINATION);
    assertThat(halt.getMessage()).isEqualTo("HALT exit 5");
  }

  @Test
  void nullAndTheBooleansAreCopiedAsTheirKinds() {
    assertThat(ApiJqj.copy(JqNull.NULL)).isEqualTo(JvValue.Constant.NULL);
    assertThat(ApiJqj.copy(JqBool.TRUE)).isEqualTo(JvValue.Constant.TRUE);
    assertThat(ApiJqj.copy(JqBool.FALSE)).isEqualTo(JvValue.Constant.FALSE);
  }

  @Test
  void compilingReadingAndRereadingEndWithTheStubsAsJqjExceptions() {
    ApiJqj jqj = new ApiJqj();

    assertThatThrownBy(() -> jqj.compile(".")).isInstanceOfSatisfying(JqjException.class, e -> assertThat(e.stub()).isTrue());
    assertThatThrownBy(() -> jqj.read("1")).isInstanceOfSatisfying(JqjException.class, e -> assertThat(e.stub()).isTrue());
    assertThatThrownBy(() -> jqj.reread("1")).isInstanceOfSatisfying(JqjException.class, e -> assertThat(e.stub()).isTrue());
  }

  @Test
  void theStubMarkIsThePrefixOfJqjsStubs() {
    assertThat(new JqjException(JqjException.Kind.COMPILE, "jqj stub: x", List.of()).stub()).isTrue();
    assertThat(new JqjException(JqjException.Kind.FILTER, null, List.of()).stub()).isFalse();
  }
}
