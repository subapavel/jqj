package io.github.subapavel.jqj.internal.parse;

/** A token of the filter text: its kind and its chars {@code [start, end)}. */
record Token(Kind kind, int start, int end) {
  enum Kind {
    DOT,
    END
  }
}
