package io.github.subapavel.jqj.cli;

/**
 * The input stream (GLOSSARY) of one run, as spec 1 reads it: all of stdin, or all of the one file argument, holding
 * no document or one (#22, "Command line"; several documents and files are spec 5's).
 */
final class Input {

  private Input() {}

  /** Whether {@code stream} holds no document: nothing, or only jq's whitespace. jq then runs nothing and exits 0. */
  static boolean holdsNoDocument(byte[] stream) {
    for (byte b : stream) {
      if (!isSpace(b)) {
        return false;
      }
    }
    return true;
  }

  /** jq's whitespace between documents and tokens (ADR-0015): SP, TAB, CR and LF. */
  static boolean isSpace(byte b) {
    return b == ' ' || b == '\t' || b == '\r' || b == '\n';
  }
}
