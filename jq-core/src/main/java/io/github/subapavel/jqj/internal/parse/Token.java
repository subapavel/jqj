package io.github.subapavel.jqj.internal.parse;

/** A token of the filter text: its kind and its chars {@code [start, end)}; a FIELD's name is its chars after the dot. */
record Token(Kind kind, int start, int end) {
  enum Kind {
    DOT,
    FIELD,
    PIPE,
    LPAREN,
    RPAREN,
    END
  }
}
