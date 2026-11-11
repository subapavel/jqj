package io.github.subapavel.jqj;

import java.util.List;

/**
 * A filter that does not compile, at a 1-based line and column (ADR-0016). A Stub ends with one too, its
 * message starting with {@code jqj stub: } (ADR-0024).
 */
public final class JqCompileException extends JqException {
  private static final long serialVersionUID = 1L;

  private final int line;
  private final int column;

  /** A compile error whose message is its one block. */
  public JqCompileException(String message, int line, int column) {
    super(message);
    this.line = line;
    this.column = column;
  }

  public int line() {
    return line;
  }

  public int column() {
    return column;
  }

  /** The error blocks of the message, unmodifiable: the message is the one block. */
  public List<String> blocks() {
    return List.of(getMessage());
  }
}
