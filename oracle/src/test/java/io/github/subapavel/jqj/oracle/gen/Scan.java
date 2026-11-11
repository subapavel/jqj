package io.github.subapavel.jqj.oracle.gen;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * An independent reading of one generated document, written from the parameter table and not from the generator: it
 * checks that the bytes are one value inside jq's accept set with none of the excluded shapes (ADR-0025), and it
 * reports the nesting depth and the bare tokens (numbers and literals) outside strings.
 */
final class Scan {

  private static final Pattern STRICT_NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

  private final byte[] b;
  private int i;
  private int depth;
  /** The deepest nesting of arrays and objects: 0 for a scalar document. */
  int maxDepth;
  /** Every token outside a string that is no punctuation: numbers, lenient spellings and literals. */
  final List<String> tokens = new ArrayList<>();

  private Scan(byte[] b) {
    this.b = b;
  }

  /** Reads a document, failing with the offset and the reason on any shape outside the table. */
  static Scan of(byte[] doc) {
    Scan s = new Scan(doc);
    s.document();
    return s;
  }

  /** True for a bare token that is a strict JSON number. */
  static boolean strict(String token) {
    return STRICT_NUMBER.matcher(token).matches();
  }

  /** True for a bare token that is neither a literal nor a strict number. */
  static boolean lenient(String token) {
    return !token.equals("true") && !token.equals("false") && !token.equals("null") && !strict(token);
  }

  private void document() {
    if (b.length == 0) fail("an empty document");
    for (int k = 0; k < b.length; k++) {
      if (b[k] == 0) fail("a raw NUL at " + k);
      if (b[k] == 0x0b || b[k] == 0x0c) fail("a raw VT or FF at " + k);
      if (k > 1 && (b[k - 1] & 0xff) == 0xef && (b[k] & 0xff) == 0xbb) fail("an inner BOM or part of one at " + (k - 1));
    }
    if ((b[0] & 0xff) == 0xef) {
      if (b.length < 3 || (b[1] & 0xff) != 0xbb || (b[2] & 0xff) != 0xbf) fail("a partial BOM");
      i = 3;
    }
    ws();
    if (i == b.length) fail("whitespace only");
    value();
    ws();
    if (i != b.length) fail("a second value or junk at " + i);
  }

  private void ws() {
    while (i < b.length && (b[i] == ' ' || b[i] == '\t' || b[i] == '\n' || b[i] == '\r')) i++;
  }

  private void value() {
    if (i == b.length) fail("a value is missing at the end");
    switch (b[i]) {
      case '[' -> container(']', false);
      case '{' -> container('}', true);
      case '"' -> string();
      default -> token();
    }
  }

  private void container(char close, boolean object) {
    i++;
    depth++;
    maxDepth = Math.max(maxDepth, depth);
    ws();
    if (i < b.length && b[i] == close) {
      i++;
      depth--;
      return;
    }
    while (true) {
      if (object) {
        ws();
        if (i == b.length || b[i] != '"') fail("a key that is no string at " + i);
        string();
        ws();
        expect(':');
      }
      ws();
      value();
      ws();
      if (i < b.length && b[i] == ',') {
        i++;
        continue;
      }
      expect(close);
      depth--;
      return;
    }
  }

  private void expect(char c) {
    if (i == b.length || b[i] != c) fail("'" + c + "' expected at " + i);
    i++;
  }

  private void string() {
    int start = i++;
    while (true) {
      if (i == b.length) fail("a string from " + start + " is not closed");
      int c = b[i] & 0xff;
      if (c == '"') {
        i++;
        return;
      }
      if (c < 0x20) fail("a raw control byte in a string at " + i);
      if (c != '\\') {
        i++;
        continue;
      }
      if (i + 1 == b.length) fail("a lone backslash at " + i);
      char e = (char) b[i + 1];
      if ("\"\\/bfnrt".indexOf(e) >= 0) {
        i += 2;
        continue;
      }
      if (e != 'u') fail("an unknown escape at " + i);
      int u = hex4(i + 2);
      i += 6;
      if (u >= 0xd800 && u <= 0xdbff) {
        if (i + 6 > b.length || b[i] != '\\' || b[i + 1] != 'u') fail("a lone high surrogate before " + i);
        int low = hex4(i + 2);
        if (low < 0xdc00 || low > 0xdfff) fail("a high surrogate without its low one before " + i);
        i += 6;
      }
    }
  }

  private int hex4(int at) {
    if (at + 4 > b.length) fail("a short \\u escape at " + at);
    int v = 0;
    for (int k = at; k < at + 4; k++) {
      int d = Character.digit((char) b[k], 16);
      if (d < 0) fail("a bad hex digit at " + k);
      v = v * 16 + d;
    }
    return v;
  }

  private void token() {
    int start = i;
    while (i < b.length && " \t\n\r,:[]{}\"".indexOf(b[i]) < 0) i++;
    if (i == start) fail("a token is missing at " + i);
    tokens.add(new String(b, start, i - start, StandardCharsets.ISO_8859_1));
  }

  private static void fail(String why) {
    throw new AssertionError(why);
  }
}
