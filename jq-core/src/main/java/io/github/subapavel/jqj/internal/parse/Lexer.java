package io.github.subapavel.jqj.internal.parse;

import io.github.subapavel.jqj.internal.Stub;

/**
 * Splits a filter into tokens. It skips SP, TAB, CR and LF, jq's whitespace (observed on jq-ref 1.8.2, #33); only
 * {@code .} is built, any other char is a Stub.
 */
final class Lexer {
  private final String text;
  private int p;

  Lexer(String text) {
    this.text = text;
  }

  /** The next token, {@link Token.Kind#END} at the end of the text. */
  Token next() {
    while (p < text.length() && isSpace(text.charAt(p))) {
      p++;
    }
    if (p == text.length()) {
      return new Token(Token.Kind.END, p, p);
    }
    if (text.charAt(p) != '.') {
      throw Stub.unbuilt("the token at char " + p + " of the filter");
    }
    p++;
    return new Token(Token.Kind.DOT, p - 1, p);
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n';
  }
}
