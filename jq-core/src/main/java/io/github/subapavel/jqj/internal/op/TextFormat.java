package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;

/**
 * jq's {@code @text} (#61): its input as {@code tostring} gives it, a string itself and any other value its compact
 * text (jq-ref 1.8.2, recorded: literal/format-text-*).
 */
public final class TextFormat implements Fn0 {
  @Override
  public Object apply(Object in) {
    return in instanceof JqString ? in : BuiltString.of(((JqValue) in).toJson());
  }
}
