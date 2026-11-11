package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Source;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The compiled identity (#33 design, section 1): {@code Compiler.compile(Node)} gives an {@code Executable} whose push
 * form emits its input (ADR-0008). The inputs are plain objects, because this module never looks inside a value
 * (founding architecture section 14); identity is checked by reference.
 */
class CompilerTest {

  /** The IR of the filter text {@code .}: one {@code Identity} over the characters [0, 1). */
  private static Executable identity() {
    return Compiler.compile(new Identity(new Source(0, 1)));
  }

  @Test
  void identityEmitsItsInputOnce() {
    Executable executable = identity();
    Object first = new Object();
    Object second = new Object();
    List<Object> emitted = new ArrayList<>();

    boolean ranOut =
        executable.run(
            first,
            v -> {
              emitted.add(v);
              return true;
            });

    assertThat(emitted).as("results of . for the first input").singleElement().isSameAs(first);
    // ADR-0008: run answers true when its results ran out, the sink never having stopped it.
    assertThat(ranOut).as("run's answer when the sink never stops").isTrue();

    // The same Executable runs again on another input and keeps nothing of the first run.
    emitted.clear();
    boolean ranOutAgain =
        executable.run(
            second,
            v -> {
              emitted.add(v);
              return true;
            });
    assertThat(emitted).as("results of . for the second input").singleElement().isSameAs(second);
    assertThat(ranOutAgain).as("run's answer on the second input").isTrue();
  }

  @Test
  void identityReturnsTheSinksStop() {
    // ADR-0008: "If emit returns false, that is a stop: the producer emits nothing more and returns false at once."
    Object input = new Object();
    List<Object> emitted = new ArrayList<>();

    boolean ranOut =
        identity()
            .run(
                input,
                v -> {
                  emitted.add(v);
                  return false;
                });

    assertThat(ranOut).as("run's answer after the sink stopped it").isFalse();
    assertThat(emitted).as("results handed over before the stop").singleElement().isSameAs(input);
  }
}
