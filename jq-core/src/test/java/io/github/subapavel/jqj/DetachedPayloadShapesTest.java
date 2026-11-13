package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.read;
import static io.github.subapavel.jqj.RunBudgets.unlimited;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
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
