package io.github.subapavel.jqj.bench;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The synthetic {@code orders} input of ADR-0019, the input of G1, G6 and G7:
 * {@code {"orders":[{id,total,k}…],"items":[{sku,price}…],"a":{"b":{"c":"x"}}}}. The bytes are a pure function of the
 * size, so JMH (which generates them in its setup) and {@code bench/jq-ref.sh} (which writes them with {@link #main})
 * time the same document.
 */
public final class Inputs {

  /** The cell sizes of ADR-0019, as the lower-case labels of ADR-0024's bench ids. */
  public enum Size {
    K1("1k", 1_024),
    K100("100k", 102_400),
    M10("10m", 10_485_760);

    private final String label;
    private final int bytes;

    Size(String label, int bytes) {
      this.label = label;
      this.bytes = bytes;
    }

    /** The size as it appears in a bench id, such as {@code 10m}. */
    public String label() {
      return label;
    }

    /** The document's length in bytes. */
    public int bytes() {
      return bytes;
    }
  }

  private static final String HEAD = "{\"orders\":[";
  private static final String MIDDLE = "],\"items\":[";
  private static final String TAIL = "],\"a\":{\"b\":{\"c\":\"x\"}}}";

  private Inputs() {}

  /**
   * The document of {@code size}: as many (order, item) pairs as fit, then spaces and one LF up to exactly
   * {@link Size#bytes()}.
   */
  public static byte[] orders(Size size) {
    int bytes = size.bytes();
    StringBuilder orders = new StringBuilder();
    StringBuilder items = new StringBuilder();
    int fixed = HEAD.length() + MIDDLE.length() + TAIL.length() + 1;
    for (int i = 0; ; i++) {
      String order = order(i);
      String item = item(i);
      int comma = i == 0 ? 0 : 1;
      if (fixed + orders.length() + items.length() + comma + order.length() + comma + item.length() > bytes) {
        break;
      }
      if (comma == 1) {
        orders.append(',');
        items.append(',');
      }
      orders.append(order);
      items.append(item);
    }
    StringBuilder doc = new StringBuilder(bytes).append(HEAD).append(orders).append(MIDDLE).append(items).append(TAIL);
    doc.repeat(' ', bytes - 1 - doc.length()).append('\n');
    return doc.toString().getBytes(US_ASCII);
  }

  /** Order {@code i}: a total of 0.00 to 199.99 in canonical form, and one of 64 keys {@code k}. */
  private static String order(int i) {
    int cents = (int) ((i * 7_919L + 13) % 20_000);
    return "{\"id\":" + i + ",\"total\":" + decimal(cents) + ",\"k\":\"k" + (i % 64) + "\"}";
  }

  /** Item {@code i}: an integer price of 1 to 9999. */
  private static String item(int i) {
    return "{\"sku\":\"s" + i + "\",\"price\":" + ((i * 4_730L) % 9_999 + 1) + "}";
  }

  /** {@code cents} / 100 without trailing zeros, so the number's token is the one jq prints. */
  private static String decimal(int cents) {
    String whole = Integer.toString(cents / 100);
    int fraction = cents % 100;
    if (fraction == 0) {
      return whole;
    }
    return fraction % 10 == 0 ? whole + "." + fraction / 10 : whole + "." + (fraction < 10 ? "0" : "") + fraction;
  }

  /**
   * Writes {@code orders-<label>.json} for every {@link Size} into the directory {@code args[0]}, which it creates.
   *
   * @throws IOException if a file cannot be written
   */
  public static void main(String[] args) throws IOException {
    Path dir = Files.createDirectories(Path.of(args[0]));
    for (Size size : Size.values()) {
      Files.write(dir.resolve("orders-" + size.label() + ".json"), orders(size));
    }
  }
}
