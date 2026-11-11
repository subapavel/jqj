package io.github.subapavel.jqj.bench;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import io.github.subapavel.jqj.bench.Inputs.Size;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** The `orders` input of ADR-0019 for the bench ids of ADR-0024: its size per id, its shape and its determinism. */
class InputsTest {

  /** Every ADR-0024 bench id with the input size it runs on. */
  static Stream<Arguments> benchIds() {
    return Stream.of(
        argumentSet("G1.1k", Size.K1, 1_024),
        argumentSet("G1.100k", Size.K100, 102_400),
        argumentSet("G1.10m", Size.M10, 10_485_760),
        argumentSet("G6.compact.10m", Size.M10, 10_485_760),
        argumentSet("G6.pretty.10m", Size.M10, 10_485_760),
        argumentSet("G7.engine.1k", Size.K1, 1_024),
        argumentSet("G7.process.1k", Size.K1, 1_024));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("benchIds")
  void theInputOfEachBenchIdHasExactlyItsSize(Size size, int bytes) {
    assertThat(size.bytes()).isEqualTo(bytes);
    assertThat(Inputs.orders(size)).hasSize(bytes);
  }

  /**
   * The digests freeze the generator: jqj's JMH times and jq-ref's times are comparable across commits only while the
   * bytes stay the same. A changed generator is a new input, recorded as such in bench/RESULTS.md.
   */
  static Stream<Arguments> digests() {
    return Stream.of(
        argumentSet("1k", Size.K1, "6f9455ed0d9c05f80cb263cac7bb421833e63f26c72898602fb5728998844cfc"),
        argumentSet("100k", Size.K100, "cdd6d4b62eff0b6d58e17445d9e53bad573562cf79bd4955496641501ea86b91"),
        argumentSet("10m", Size.M10, "2c8135089c4516ad77990e3ca01e2a36f4cf61b3843c269acaeec227dab1a547"));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("digests")
  void theBytesOfEachSizeAreFixed(Size size, String sha256) {
    assertThat(sha256(Inputs.orders(size))).isEqualTo(sha256);
  }

  @ParameterizedTest
  @EnumSource(Size.class)
  void twoCallsGiveTheSameBytes(Size size) {
    assertThat(Inputs.orders(size)).isEqualTo(Inputs.orders(size));
  }

  /** ADR-0019's shape, with `.a.b.c` after both arrays so G1 passes over the whole document. */
  @ParameterizedTest
  @EnumSource(Size.class)
  void theDocumentIsOrdersThenItemsThenA(Size size) {
    String text = new String(Inputs.orders(size), US_ASCII);
    assertThat(text).startsWith("{\"orders\":[{\"id\":0,\"total\":");
    assertThat(text).contains("],\"items\":[{\"sku\":\"s0\",\"price\":");
    assertThat(text.stripTrailing()).endsWith("],\"a\":{\"b\":{\"c\":\"x\"}}}");
    assertThat(text).endsWith("\n");
  }

  @Test
  void theFirstRecordsAreAsDocumented() {
    String text = new String(Inputs.orders(Size.K1), US_ASCII);
    assertThat(text)
        .startsWith(
            "{\"orders\":[{\"id\":0,\"total\":0.13,\"k\":\"k0\"},{\"id\":1,\"total\":79.32,\"k\":\"k1\"},");
    assertThat(text).contains("],\"items\":[{\"sku\":\"s0\",\"price\":1},{\"sku\":\"s1\",\"price\":4731},");
  }

  @Test
  void mainWritesOneFilePerSize(@TempDir Path dir) throws IOException {
    Inputs.main(new String[] {dir.toString()});
    for (Size size : Size.values()) {
      assertThat(Files.readAllBytes(dir.resolve("orders-" + size.label() + ".json"))).isEqualTo(Inputs.orders(size));
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
