package io.github.subapavel.jqj.internal.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.subapavel.jqj.JqCompileException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * ADR-0024: the four views exist so the kinds can be sealed, and every public method of a view is a
 * Stub until the reader spec builds them. Each method is called with zero and null arguments.
 */
class ViewStubTest {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String MARK = "jqj stub: ";

  /** ADR-0016's read accessors of each kind (and JqValue's toJson), written from the API slice. */
  private static final Map<Class<?>, Set<String>> KIND_METHODS =
      Map.of(
          NumberView.class, Set.of("toJson()", "doubleValue()", "fitsLong()", "longValue()", "decimalValue()"),
          StringView.class, Set.of("toJson()", "value()", "utf8()"),
          ArrayView.class, Set.of("toJson()", "length()", "get(int)"),
          ObjectView.class, Set.of("toJson()", "size()", "get(String)", "forEach(BiConsumer)"));

  @TestFactory
  Stream<DynamicTest> everyViewMethodIsAStub() throws ReflectiveOperationException {
    List<DynamicTest> tests = new ArrayList<>();
    for (Class<?> view : List.of(NumberView.class, StringView.class, ArrayView.class, ObjectView.class)) {
      Object instance = newView(view);
      List<Method> methods =
          Arrays.stream(view.getMethods())
              .filter(m -> !m.isSynthetic() && !isObjectMethod(m))
              .sorted(Comparator.comparing(ViewStubTest::signature))
              .toList();
      assertThat(methods.stream().map(ViewStubTest::signature).collect(Collectors.toSet()))
          .as("public methods of %s", view.getSimpleName())
          .containsAll(KIND_METHODS.get(view));
      for (Method method : methods) {
        tests.add(
            dynamicTest(
                view.getSimpleName() + "." + signature(method),
                () ->
                    assertThatThrownBy(() -> invoke(method, instance))
                        .isInstanceOf(JqCompileException.class)
                        .hasMessageStartingWith(MARK)));
      }
    }
    return tests.stream();
  }

  private static Object newView(Class<?> view) throws ReflectiveOperationException {
    Constructor<?> constructor =
        Arrays.stream(view.getDeclaredConstructors())
            .min(Comparator.comparingInt(Constructor::getParameterCount))
            .orElseThrow(() -> new AssertionError("no constructor in " + view.getName()));
    constructor.setAccessible(true);
    return constructor.newInstance(zeros(constructor.getParameterTypes()));
  }

  private static void invoke(Method method, Object instance) throws Throwable {
    try {
      method.invoke(Modifier.isStatic(method.getModifiers()) ? null : instance, zeros(method.getParameterTypes()));
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  /** The default value of each parameter type: 0 or false for a primitive, null otherwise. */
  private static Object[] zeros(Class<?>[] types) {
    return Arrays.stream(types).map(t -> Array.get(Array.newInstance(t, 1), 0)).toArray();
  }

  /** equals, hashCode, toString and Object's final methods are not part of a kind's contract. */
  private static boolean isObjectMethod(Method m) {
    try {
      Object.class.getMethod(m.getName(), m.getParameterTypes());
      return true;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  private static String signature(Method m) {
    return m.getName() + Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName).collect(Collectors.joining(", ", "(", ")"));
  }
}
