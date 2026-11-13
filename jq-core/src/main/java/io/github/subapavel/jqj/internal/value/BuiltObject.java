package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.internal.json.Printer;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.util.function.BiConsumer;

/**
 * An object built at run time (ADR-0003, #64): its key list, distinct keys in their first place, and the last value of
 * each, never written once the object is made. The key list is its construction site's shape when every key there is
 * constant, shared by every object the site builds, or its own for a growable object, whose keys a run computes
 * (GLOSSARY "Shape", "Growable object"). A key is a document's string or a built one. Public only so the kind can
 * permit it. Equality is the object's, never jq's (ADR-0026).
 */
public final class BuiltObject implements JqObject, Members, Keyed {
  /** The keys, distinct, {@code [0, size)} of it. */
  private final JqString[] keys;

  /** The value of each key, each a {@link JqValue}, {@code [0, size)} of it. */
  private final Object[] values;

  private final int size;

  /**
   * The ownership mark of ADR-0022: every built class carries it from the ticket that creates the class, and it stays
   * null until spec 3 (spec-1 grilling round 1, item 9).
   */
  private final Object owner;

  /**
   * The object of the distinct {@code keys} and their {@code values}, in order, which it keeps without a copy: the
   * caller never writes either array again, and a shape's keys are never written at all.
   */
  public BuiltObject(JqString[] keys, Object[] values) {
    this(keys, values, keys.length);
  }

  private BuiltObject(JqString[] keys, Object[] values, int size) {
    this.keys = keys;
    this.values = values;
    this.size = size;
    this.owner = null;
  }

  /**
   * The growable object of {@code pairs}, a key and its value each, every key a string: a repeated key keeps its first
   * place and its last value, as jq's object insert does (GLOSSARY "Key list").
   */
  public static BuiltObject ofPairs(Object[] pairs) {
    int n = pairs.length >>> 1;
    JqString[] keys = new JqString[n];
    Object[] values = new Object[n];
    int size = 0;
    for (int p = 0; p < pairs.length; p += 2) {
      JqString key = (JqString) pairs[p];
      int at = 0;
      while (at < size && !same(keys[at], key)) {
        at++;
      }
      if (at == size) {
        keys[size++] = key;
      }
      values[at] = pairs[p + 1];
    }
    return new BuiltObject(keys, values, size);
  }

  /** Whether two keys are one: their UTF-8 bytes are equal. */
  private static boolean same(JqString a, JqString b) {
    return a.utf8().mismatch(b.utf8()) == -1;
  }

  /** Whether {@code key} spells {@code name}, compared by code point as a document's keys are (ObjectView). */
  private static boolean spells(JqString key, String name) {
    return switch (key) {
      case BuiltString s -> ObjectView.spells(s.bytes(), 0, s.byteLength(), name);
      case StringView v -> ObjectView.spells(v.doc().bytes(), v.doc().live()[v.index()], name);
    };
  }

  /** jq's compact text (ADR-0015). */
  @Override
  public String toJson() {
    return Printer.json(this);
  }

  @Override
  public int size() {
    return size;
  }

  /** The value of {@code key}, or Java {@code null} when the object has no such key (ADR-0026). */
  @Override
  public JqValue get(String key) {
    return valueAt(find(key));
  }

  /**
   * {@link #get(String)} for a filter: one tick per key compared, up to the one that spells {@code key}, or every key
   * when none does (ADR-0034).
   */
  @Override
  public JqValue get(RunState state, String key) {
    int i = find(key);
    state.tick(Math.min(i + 1, size));
    return valueAt(i);
  }

  /** The index of the key that spells {@code key}, or {@code size} for none. */
  private int find(String key) {
    int i = 0;
    while (i < size && !spells(keys[i], key)) {
      i++;
    }
    return i;
  }

  /** The value at {@code i}, or Java {@code null} for {@code size}. */
  private JqValue valueAt(int i) {
    return i < size ? (JqValue) values[i] : null;
  }

  /** Each key, decoded, with its value, in the key list's order. */
  @Override
  public void forEach(BiConsumer<? super String, ? super JqValue> action) {
    for (int i = 0; i < size; i++) {
      action.accept(keys[i].value(), (JqValue) values[i]);
    }
  }

  /** The key at {@code i}, {@code 0 <= i < size()}, for the writer. */
  public JqString key(int i) {
    return keys[i];
  }

  /** The value at {@code i}, {@code 0 <= i < size()}, for the writer. */
  public JqValue value(int i) {
    return (JqValue) values[i];
  }

  /** The cursor of the first entry, its index, or -1 for an empty object. */
  @Override
  public int first() {
    return size > 0 ? 0 : -1;
  }

  /** The cursor after the entry at {@code i}, or -1 after the last: the keys are distinct, so there is no scan. */
  @Override
  public int next(RunState state, int i) {
    return i + 1 < size ? i + 1 : -1;
  }

  /** The value of the entry at {@code i}. */
  @Override
  public JqValue member(RunState state, int i) {
    return (JqValue) values[i];
  }
}
