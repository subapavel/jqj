package io.github.subapavel.jqj.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** Build skeleton (ticket 23): runs one class of each upstream module from the oracle, on the class path. */
class SkeletonTest {
  private static Object call(String className, String method, Class<?>[] types, Object... args) throws Exception {
    Method m = Class.forName(className).getDeclaredMethod(method, types);
    m.setAccessible(true);
    return m.invoke(null, args);
  }

  @Test
  void queryCoreRunsFromTheOracle() throws Exception {
    Object r = call("io.github.subapavel.jqj.query.internal.Placeholder", "twice", new Class<?>[] {int.class}, 4);
    assertThat(r).isEqualTo(8);
  }

  @Test
  void jqCoreRunsFromTheOracle() throws Exception {
    Object r = call("io.github.subapavel.jqj.internal.Placeholder", "module", new Class<?>[] {});
    assertThat(r).isEqualTo("jq-core");
  }
}
