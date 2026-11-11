package io.github.subapavel.jqj.oracle.diff;

/**
 * A case's verdict in the oracle report (ADR-0020).
 *
 * @param detail one line, no tab: why, for the report's detail column
 * @param diff the readable comparison for a failing test, empty when the case passes
 */
public record Verdict(Kind kind, String detail, String diff) {

  public enum Kind {
    PASS,
    FAIL,
    STALE,
    CRASH
  }
}
