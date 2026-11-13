package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.query.run.Charges;
import io.github.subapavel.jqj.query.run.RunState;

/**
 * The arrays an operator's result is built in, each charged before it is allocated (ADR-0035): a string's bytes as a
 * string, a built array's slots as a container and its elements. A length past what the JVM allocates ends the run
 * with the bytes-built termination instead, whatever the limit.
 */
final class Built {
  /** The longest array built: {@code jdk.internal.util.ArraysSupport.SOFT_MAX_ARRAY_LENGTH}, below the VMs' limits. */
  static final int MAX_LENGTH = Integer.MAX_VALUE - 8;

  private Built() {}

  /** The bytes of a new string of {@code length} bytes. */
  static byte[] string(RunState state, long length) {
    if (length > MAX_LENGTH) {
      throw state.overBuilt();
    }
    state.chargeBuilt(Charges.STRING + length);
    return new byte[(int) length];
  }

  /** The slots of a new array of {@code length} elements. */
  static Object[] elements(RunState state, long length) {
    if (length > MAX_LENGTH) {
      throw state.overBuilt();
    }
    state.chargeBuilt(Charges.CONTAINER + Charges.ELEMENT * length);
    return new Object[(int) length];
  }

  /** The ticks of a loop over {@code bytes} bytes: one per started KiB, the writer's rate (ADR-0008, ADR-0015). */
  static int kibibytes(long bytes) {
    return (int) ((bytes + 1023) >>> 10);
  }
}
