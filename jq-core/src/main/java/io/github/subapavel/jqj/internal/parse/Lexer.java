package io.github.subapavel.jqj.internal.parse;

import io.github.subapavel.jqj.internal.Stub;

/**
 * Splits a filter into tokens. It skips SP, TAB, CR and LF, jq's whitespace (observed on jq-ref 1.8.2, #33). Built
 * (#34 design section 4): {@code .}, a {@code .} followed by {@code [A-Za-z_][A-Za-z0-9_]*} as one FIELD token (so
 * {@code .and} and {@code .__loc__} are plain names), {@code |}, {@code (} and {@code )}. {@code ..} and any other
 * char are Stubs.
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
    int start = p;
    if (p == text.length()) {
      return new Token(Token.Kind.END, start, p);
    }
    Token.Kind kind =
        switch (text.charAt(p++)) {
          case '.' -> field();
          case '|' -> Token.Kind.PIPE;
          case '(' -> Token.Kind.LPAREN;
          case ')' -> Token.Kind.RPAREN;
          default -> throw Stub.unbuilt("the token at char " + start + " of the filter");
        };
    return new Token(kind, start, p);
  }

  /** After a dot: a name makes it a FIELD and is taken whole; a second dot ({@code ..}) is a Stub; else a DOT. */
  private Token.Kind field() {
    if (p < text.length() && text.charAt(p) == '.') {
      throw Stub.unbuilt("the recursion ..");
    }
    if (p == text.length() || !isNameStart(text.charAt(p))) {
      return Token.Kind.DOT;
    }
    do {
      p++;
    } while (p < text.length() && (isNameStart(text.charAt(p)) || isDigit(text.charAt(p))));
    return Token.Kind.FIELD;
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n';
  }

  private static boolean isNameStart(char c) {
    return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_';
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }
}
