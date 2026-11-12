package io.github.subapavel.jqj.internal.parse;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A filter's text as UTF-8 bytes, with jq's positions and error blocks (ADR-0031, GLOSSARY "Error block"). An offset
 * is a byte offset into the text; its line is the LFs before it plus 1, its column the bytes since its line's start
 * plus 1 (jq's {@code locfile}). A CR is no line end.
 */
final class FilterText {
  private final byte[] bytes;

  FilterText(String text) {
    this.bytes = text.getBytes(UTF_8);
  }

  /** The text's UTF-8 bytes; never written. */
  byte[] bytes() {
    return bytes;
  }

  /** The 1-based line of the byte at {@code offset}. */
  int line(int offset) {
    int line = 1;
    for (int i = 0; i < offset; i++) {
      if (bytes[i] == '\n') {
        line++;
      }
    }
    return line;
  }

  /** The 1-based column of the byte at {@code offset}, in bytes. */
  int column(int offset) {
    return offset - lineStart(offset) + 1;
  }

  /**
   * jq's error block for {@code message} at the bytes {@code [start, end)} ({@code locfile_locate} under jq's
   * {@code jq: error: %s}): the message, the line and column of {@code start}, then {@code start}'s whole line indented
   * by four spaces, then carets under the span from {@code start} that stop at that line's end.
   */
  String block(String message, int start, int end) {
    int from = lineStart(start);
    int to = start;
    while (to < bytes.length && bytes[to] != '\n') {
      to++;
    }
    return "jq: error: "
        + message
        + " at <top-level>, line "
        + line(start)
        + ", column "
        + column(start)
        + ":\n    "
        + new String(bytes, from, to - from, UTF_8)
        + "\n"
        + " ".repeat(4 + start - from)
        + "^".repeat(Math.min(end, to) - start);
  }

  /** The offset of the first byte of the line that holds the byte at {@code offset}. */
  private int lineStart(int offset) {
    int i = offset;
    while (i > 0 && bytes[i - 1] != '\n') {
      i--;
    }
    return i;
  }
}
