package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.read;
import static io.github.subapavel.jqj.RunBudgets.unlimited;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The shapes a detached error payload keeps (ADR-0016, ADR-0039), beside ErrorPayloadTest: containers longer than the
 * detach's first capacity, an object with a duplicate key, empty containers, a built container holding views, and a
 * document's {@code -0}. Each payload is read after the Workspace's next read, which makes every earlier view stale.
 * The expected texts are jq-ref 1.8.2's, observed 2026-11-13: {@code .a | error} on each input prints the payload after
 * {@code (not a string): }.
 */
class DetachedPayloadShapesTest {

  /** The payload {@code filter} raises on {@code json}, read after the next read over the same tape. */
  private static JqValue payloadAfterTheNextRead(String filter, String json) {
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, json.getBytes(UTF_8)).root();
    JqFilterException e =
        assertThatExceptionOfType(JqFilterException.class)
            .as("%s on %s", filter, json)
            .isThrownBy(() -> engine.compile(filter).run(root, NONE, new Results(), workspace))
            .actual();
    read(workspace, "[2,3,4,5,6,7,8,9]".getBytes(UTF_8));
    return e.value();
  }

  @Test
  void aViewArrayLongerThanTheFirstCapacityKeepsEveryElement() {
    JqValue payload = payloadAfterTheNextRead(".a | error", "{\"a\":[1,2,3,4,5,6,7,8,9,10]}");
    assertThat(((JqArray) payload).length()).as("length of the payload").isEqualTo(10);
    assertThat(payload.toJson()).as("the payload's text").isEqualTo("[1,2,3,4,5,6,7,8,9,10]");
  }

  @Test
  void aViewObjectOfMoreKeysThanTheFirstCapacityKeepsTheLastValueOfADuplicateKey() {
    JqValue payload = payloadAfterTheNextRead(".a | error", "{\"a\":{\"k1\":1,\"k2\":2,\"k3\":3,\"k4\":4,\"k5\":5,\"k1\":6}}");
    assertThat(((JqObject) payload).size()).as("size of the payload").isEqualTo(5);
    assertThat(payload.toJson()).as("the payload's text").isEqualTo("{\"k1\":6,\"k2\":2,\"k3\":3,\"k4\":4,\"k5\":5}");
  }

  @Test
  void anEscapedKeyIsTheKeyItDecodesTo() {
    // jq-ref: .a | error on {"a":{"a":1,"b":2,"\u0061":3}} prints {"a":3,"b":2}: keys are one by their decoded bytes.
    JqValue payload = payloadAfterTheNextRead(".a | error", "{\"a\":{\"a\":1,\"b\":2,\"\\u0061\":3}}");
    assertThat(payload.toJson()).as("the payload's text").isEqualTo("{\"a\":3,\"b\":2}");
  }

  @Test
  void emptyViewContainersStayEmpty() {
    JqValue payload = payloadAfterTheNextRead(".a | error", "{\"a\":{\"x\":[],\"y\":{}}}");
    assertThat(((JqArray) ((JqObject) payload).get("x")).length()).as("length of payload.x").isZero();
    assertThat(((JqObject) ((JqObject) payload).get("y")).size()).as("size of payload.y").isZero();
    assertThat(payload.toJson()).as("the payload's text").isEqualTo("{\"x\":[],\"y\":{}}");
  }

  @Test
  void aBuiltPayloadHoldingViewsIsDetachedToo() {
    // jq-ref: {a: .a, b: [.a]} | error on {"a":"s\nt"} prints {"a":"s\nt","b":["s\nt"]}.
    JqValue payload = payloadAfterTheNextRead("{a: .a, b: [.a]} | error", "{\"a\":\"s\\nt\"}");
    JqObject object = (JqObject) payload;
    assertThat(((JqString) object.get("a")).value()).as("payload.a").isEqualTo("s\nt");
    assertThat(((JqString) ((JqArray) object.get("b")).get(0)).value()).as("payload.b[0]").isEqualTo("s\nt");
    assertThat(payload.toJson()).as("the payload's text").isEqualTo("{\"a\":\"s\\nt\",\"b\":[\"s\\nt\"]}");
  }

  @Test
  void aReadObjectOf10To5KeysDetachesInLinearTime() {
    // Review ruling 3 on #68: the detach runs after the run, where no budget, deadline or interrupt reaches it, so it
    // must be linear. A walk that checks each key against the earlier ones compares 10^10 / 2 pairs, far past the
    // limit (a time witness). The last key repeats the first: it keeps the first position and takes the last value.
    StringBuilder json = new StringBuilder("{");
    for (int i = 0; i < 100_000; i++) {
      json.append("\"k").append(i).append("\":").append(i).append(',');
    }
    json.append("\"k0\":-1}");
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, json.toString().getBytes(UTF_8)).root();
    JqFilter filter = engine.compile("error");
    JqFilterException e =
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> assertThatExceptionOfType(JqFilterException.class).isThrownBy(() -> filter.run(root, NONE, new Results(), workspace)).actual(),
            "error on a read object of 10^5 keys");
    JqObject payload = (JqObject) e.value();
    assertThat(payload.size()).as("size of the payload").isEqualTo(100_000);
    assertThat(((JqNumber) payload.get("k0")).longValue()).as("payload.k0, the repeated key's last value").isEqualTo(-1L);
    assertThat(((JqNumber) payload.get("k99999")).longValue()).as("payload.k99999").isEqualTo(99_999L);
    assertThat(payload.toJson()).as("the payload's text").startsWith("{\"k0\":-1,\"k1\":1,").endsWith(",\"k99999\":99999}");
  }

  @Test
  void aBuiltObjectWithADocumentsKeyDetachesTheKey() {
    // jq-ref: ({x: 1} + .a) | error on {"a":{"k":"v"}} prints {"x":1,"k":"v"}. The merge keeps the document's key, so
    // the built object's walk copies a view key.
    JqObject payload = (JqObject) payloadAfterTheNextRead("({x: 1} + .a) | error", "{\"a\":{\"k\":\"v\"}}");
    assertThat(((JqString) payload.get("k")).value()).as("payload.k").isEqualTo("v");
    assertThat(payload.toJson()).as("the payload's text").isEqualTo("{\"x\":1,\"k\":\"v\"}");
  }

  @Test
  void aDocumentMinusZeroKeepsItsSignAndFitsNoLong() {
    // Witness: the document's -0 fits no long as a view (ADR-0012 FITS_LONG); the detached copy keeps that flag.
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, "{\"a\":-0}".getBytes(UTF_8)).root();
    Results kept = new Results();
    assertThat(engine.compile(".a").run(root, NONE, kept, workspace)).as("run's answer for .a").isTrue();
    assertThat(((JqNumber) kept.values.getFirst()).fitsLong()).as("fitsLong() of the view -0").isFalse();

    JqNumber payload = (JqNumber) payloadAfterTheNextRead(".a | error", "{\"a\":-0}");
    assertThat(payload.toJson()).as("the payload's text").isEqualTo("-0");
    assertThat(payload.fitsLong()).as("fitsLong() of the detached -0").isFalse();
  }
}
