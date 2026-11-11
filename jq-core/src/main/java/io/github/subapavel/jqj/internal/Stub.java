package io.github.subapavel.jqj.internal;

import io.github.subapavel.jqj.JqCompileException;

/**
 * GLOSSARY "Stub", ADR-0024: a body that is not built yet ends with a {@link JqCompileException} whose
 * message starts with {@link #PREFIX}. The oracle runners score a case FAIL by that prefix.
 */
public final class Stub {
  /** The mark every Stub message starts with. */
  public static final String PREFIX = "jqj stub: ";

  private Stub() {}

  /** The exception a Stub throws; {@code what} names the construct, e.g. {@code JqEngine.compile}. */
  public static JqCompileException unbuilt(String what) {
    return new JqCompileException(PREFIX + what + " is not built yet", 1, 1);
  }
}
