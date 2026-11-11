package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqValue;
import java.util.function.BiConsumer;

/** An object of a document (ADR-0016). Public only so the kind can permit it; every method is a Stub. */
public final class ObjectView implements JqObject {
  ObjectView() {}

  @Override
  public String toJson() {
    throw Stub.unbuilt("JqObject.toJson");
  }

  @Override
  public int size() {
    throw Stub.unbuilt("JqObject.size");
  }

  @Override
  public JqValue get(String key) {
    throw Stub.unbuilt("JqObject.get");
  }

  @Override
  public void forEach(BiConsumer<? super String, ? super JqValue> action) {
    throw Stub.unbuilt("JqObject.forEach");
  }
}
