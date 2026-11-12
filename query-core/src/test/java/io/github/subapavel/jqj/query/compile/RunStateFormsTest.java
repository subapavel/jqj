package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.run.Fn0;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-0030 (#78): the forms without a run state are gone, so no caller can run outside a run. */
class RunStateFormsTest {
  private static List<String> parameterLists(Class<?> type, String name) {
    return Arrays.stream(type.getMethods())
        .filter(m -> m.getName().equals(name))
        .map(m -> Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName).toList().toString())
        .toList();
  }

  @Test
  void fn0AppliesOnlyWithARunState() {
    assertThat(parameterLists(Fn0.class, "apply")).as("Fn0.apply forms").containsExactly("[RunState, Object]");
  }

  @Test
  void executableRunsOnlyWithARunState() {
    assertThat(parameterLists(Executable.class, "run")).as("Executable.run forms").containsExactly("[RunState, Object, Sink]");
  }

  @Test
  void compilerCompilesOnlyWithAMode() {
    assertThat(parameterLists(Compiler.class, "compile")).as("Compiler.compile forms").containsExactly("[Node, CompileMode]");
  }
}
