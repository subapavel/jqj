package io.github.subapavel.jqj.internal.value;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * GLOSSARY "Exponent limits": below 1E-1147483646 a literal is rounded HALF_UP to a multiple of it. These cases round
 * up where the kept digits do not end in a carry through all of them, so the digit that grows is not the first one
 * (#36 implementer, beside CanonicalFormTest.belowEtinyRoundsHalfUp, whose round-ups grow the first digit or carry
 * through all nines). jq-ref 1.8.2, observed 2026-11-12:
 *
 * <pre>
 * printf '[1295e-1147483648,12995e-1147483648,-1995e-1147483648,1249e-1147483648,1250e-1147483648]' | jq-ref -c .
 *   -> [1.3E-1147483645,1.30E-1147483644,-2.0E-1147483645,1.2E-1147483645,1.3E-1147483645]
 * </pre>
 */
class CanonicalFormCarryTest {

  /** The canonical form of {@code token}, written from the middle of a larger array as in CanonicalFormTest. */
  private static String canonical(String token) {
    byte[] bytes = ("98" + token + "76").getBytes(US_ASCII);
    StringBuilder text = new StringBuilder();
    CanonicalForm.write(
        bytes,
        2,
        token.length(),
        new CanonicalForm.Out() {
          @Override
          public void put(byte[] src, int off, int len) {
            text.append(new String(src, off, len, US_ASCII));
          }

          @Override
          public void put(char c) {
            text.append(c);
          }
        });
    return text.toString();
  }

  @Test
  void roundingUpBelowEtinyGrowsTheLastDigitBelowNine() {
    List<String> tokens = List.of("1295e-1147483648", "12995e-1147483648", "-1995e-1147483648", "1249e-1147483648", "1250e-1147483648");
    List<String> printed = List.of("1.3E-1147483645", "1.30E-1147483644", "-2.0E-1147483645", "1.2E-1147483645", "1.3E-1147483645");
    for (int i = 0; i < tokens.size(); i++) {
      assertThat(canonical(tokens.get(i))).as("the canonical form of %s", tokens.get(i)).isEqualTo(printed.get(i));
    }
  }
}
