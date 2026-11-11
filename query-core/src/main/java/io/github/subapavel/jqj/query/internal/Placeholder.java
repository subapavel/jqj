package io.github.subapavel.jqj.query.internal;

/** Build skeleton placeholder (ticket 23): gives JaCoCo and PIT a class to measure. Deleted by the first real class. */
final class Placeholder {
  private Placeholder() {}

  /** Twice the argument. */
  static int twice(int a) {
    return 2 * a;
  }

  /** Whether the argument is below zero; only the unit test of this module reaches it. */
  static boolean isNegative(int a) {
    return a < 0;
  }
}
