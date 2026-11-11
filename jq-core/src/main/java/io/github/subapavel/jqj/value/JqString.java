package io.github.subapavel.jqj.value;

import io.github.subapavel.jqj.internal.value.StringView;
import java.lang.foreign.MemorySegment;

/** A jq string (ADR-0016). */
public sealed interface JqString extends JqValue permits StringView {
  /** The string as a {@code String}. */
  String value();

  /** The string's UTF-8 bytes, read-only; a slice of a view follows ADR-0016's lifetime rule. */
  MemorySegment utf8();
}
