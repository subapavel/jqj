package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.subapavel.jqj.query.ir.AltBind;
import io.github.subapavel.jqj.query.ir.Alternative;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.RunState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The slot rules of ADR-0038 at the IR seam, over plain objects (founding §14): a slot is bound by one Bind, by one
 * AltBind (its value slot and its group slots), or is a parameter; a group slot is read only in its AltBind's body; each
 * from-slot is in scope at its match's leaf; a parameter slot is in scope everywhere and bound by no node. A broken rule
 * is an IllegalStateException from compile, as for the rules of CommaBindTest.
 *
 * <p>Shapes the test-author fixed from ADR-0038 (no draft text reached the red): {@code AltBind(Source, Node value, int
 * slot, List<Alternative> alternatives, List<Integer> slots, Node body)} and {@code Alternative(Node match, List<Integer>
 * from)} in {@code query.ir}, {@code from} giving, for each group slot in {@code slots}' order, the alternative's own slot;
 * {@code Compiler.compile(Node, int parameters, CompileMode)}; {@code Executable.run(RunState, Object[] parameters,
 * Object in, Sink)}. A match is a chain of Binds whose innermost body is its leaf. No case here has an alternative
 * without a slot for some group slot, the "or null" of ADR-0038, whose form the ADR leaves open.
 */
class IrVerifierTest {
  private static final Source AT = new Source(0, 1);

  private static final Fn1 ID = (state, in, a) -> a;

  private static Executable compile(Node root, int parameters) {
    return Compiler.compile(root, parameters, CompileMode.OPTIMISED);
  }

  private static Intrinsic id(Node arg) {
    return new Intrinsic(AT, new Operation("id", ID), List.of(arg));
  }

  private static Var var(int slot) {
    return new Var(AT, slot);
  }

  /** An alternative whose match binds slot {@code own} to {@code id($value)} and whose one from-slot is {@code own}. */
  private static Alternative binding(int value, int own) {
    return new Alternative(new Bind(AT, id(var(value)), own, new Identity(AT)), List.of(own));
  }

  /** {@code value as $0 ?// (two alternatives, own slots 1 and 3) | body}, with group slot 2. */
  private static AltBind chain(Node value, Node body) {
    return new AltBind(AT, value, 0, List.of(binding(0, 1), binding(0, 3)), List.of(2), body);
  }

  /** The results of {@code executable} on {@code in} with {@code parameters}; the sink never stops. */
  private static List<Object> results(Executable executable, Object[] parameters, Object in) {
    List<Object> emitted = new ArrayList<>();
    boolean ranOut =
        executable.run(
            new RunState(),
            parameters,
            in,
            v -> {
              emitted.add(v);
              return true;
            });
    assertThat(ranOut).as("run's answer when the sink never stops").isTrue();
    return emitted;
  }

  @Test
  void aWellFormedAltBindCompilesAndItsBodyReadsTheGroupSlot() {
    // Witness for every rejection below: the group slot read in the body, each from-slot bound around its leaf.
    Object in = new Object();
    assertThat(results(compile(chain(new Identity(AT), id(var(2))), 0), new Object[0], in)).as("results of the well-formed chain").singleElement().isSameAs(in);
  }

  @Test
  void rejectsAGroupSlotReadInTheValue() {
    assertThatIllegalStateException().as("compile of a chain whose value reads its group slot").isThrownBy(() -> compile(chain(id(var(2)), id(var(2))), 0));
  }

  @Test
  void rejectsAGroupSlotReadAfterTheAltBind() {
    Comma after = new Comma(AT, chain(new Identity(AT), id(var(2))), id(var(2)));
    assertThatIllegalStateException().as("compile of (chain), id($2)").isThrownBy(() -> compile(after, 0));
  }

  @Test
  void rejectsAFromSlotNotInScopeAtItsLeaf() {
    // A from-slot no Bind of the match binds.
    AltBind unbound = new AltBind(AT, new Identity(AT), 0, List.of(new Alternative(new Identity(AT), List.of(1))), List.of(2), id(var(2)));
    assertThatIllegalStateException().as("compile of a match . whose from-slot is 1").isThrownBy(() -> compile(unbound, 0));

    // The second alternative names the first one's slot: bound in the tree, but not around the second leaf.
    Alternative wrong = new Alternative(new Bind(AT, id(var(0)), 3, new Identity(AT)), List.of(1));
    AltBind crossed = new AltBind(AT, new Identity(AT), 0, List.of(binding(0, 1), wrong), List.of(2), id(var(2)));
    assertThatIllegalStateException().as("compile of a second alternative whose from-slot is the first one's").isThrownBy(() -> compile(crossed, 0));

    // Witness: from its own slot, the same second alternative compiles.
    Alternative right = new Alternative(new Bind(AT, id(var(0)), 3, new Identity(AT)), List.of(3));
    assertThat(compile(new AltBind(AT, new Identity(AT), 0, List.of(binding(0, 1), right), List.of(2), id(var(2))), 0))
        .as("compile of a second alternative from its own slot")
        .isNotNull();
  }

  @Test
  void rejectsABindOfAParameterSlot() {
    assertThatIllegalStateException().as("compile of . as $0 | id($0) with one parameter").isThrownBy(() -> compile(new Bind(AT, new Identity(AT), 0, id(var(0))), 1));
    // Witnesses: with no parameter the same tree compiles, and with one a Bind of slot 1 does.
    assertThat(compile(new Bind(AT, new Identity(AT), 0, id(var(0))), 0)).as("compile of . as $0 | id($0) with no parameter").isNotNull();
    assertThat(compile(new Bind(AT, new Identity(AT), 1, id(var(1))), 1)).as("compile of . as $1 | id($1) with one parameter").isNotNull();
  }

  @Test
  void rejectsAnAltBindOfAParameterSlot() {
    // Its value slot 0 is the parameter.
    assertThatIllegalStateException().as("compile of a chain whose value slot is a parameter").isThrownBy(() -> compile(chain(new Identity(AT), id(var(2))), 1));
    // Its group slot 0 is the parameter (value slot 3, own slot 4).
    AltBind group = new AltBind(AT, new Identity(AT), 3, List.of(binding(3, 4)), List.of(0), id(var(0)));
    assertThatIllegalStateException().as("compile of a chain whose group slot is a parameter").isThrownBy(() -> compile(group, 1));
    // Witness: group slot 5 instead.
    assertThat(compile(new AltBind(AT, new Identity(AT), 3, List.of(binding(3, 4)), List.of(5), id(var(5))), 1)).as("compile of the chain with group slot 5").isNotNull();
  }

  @Test
  void rejectsAGroupSlotBoundAgain() {
    // An alternative's match binds the group slot itself.
    AltBind byMatch = new AltBind(AT, new Identity(AT), 0, List.of(binding(0, 2)), List.of(2), id(var(2)));
    assertThatIllegalStateException().as("compile of a match that binds the group slot").isThrownBy(() -> compile(byMatch, 0));
    // The value slot is also the group slot.
    AltBind byValue = new AltBind(AT, new Identity(AT), 2, List.of(binding(2, 1)), List.of(2), id(var(2)));
    assertThatIllegalStateException().as("compile of a chain whose value slot is its group slot").isThrownBy(() -> compile(byValue, 0));
    // A Bind beside the chain takes its group slot.
    Comma beside = new Comma(AT, chain(new Identity(AT), id(var(2))), new Bind(AT, new Identity(AT), 2, id(var(2))));
    assertThatIllegalStateException().as("compile of (chain), (. as $2 | id($2))").isThrownBy(() -> compile(beside, 0));
  }

  @Test
  void aParameterSlotIsReadAnywhereAndBoundByNoNode() {
    Object parameter = new Object();
    Object[] parameters = {parameter};
    assertThat(results(compile(id(var(0)), 1), parameters, new Object())).as("results of id($0) with $0 a parameter").singleElement().isSameAs(parameter);
    // In a Bind's value and body, and in a chain's match.
    Bind inner = new Bind(AT, id(var(0)), 1, id(var(1)));
    assertThat(results(compile(inner, 1), parameters, new Object())).as("results of id($0) as $1 | id($1)").singleElement().isSameAs(parameter);
    AltBind match = new AltBind(AT, new Identity(AT), 1, List.of(binding(0, 2)), List.of(3), id(var(3)));
    assertThat(results(compile(match, 1), parameters, new Object())).as("results of a chain whose match reads the parameter").singleElement().isSameAs(parameter);

    // Slot 1 is no parameter and no Bind takes it; with no parameter, slot 0 is not in scope either.
    assertThatIllegalStateException().as("compile of id($1) with one parameter").isThrownBy(() -> compile(id(var(1)), 1));
    assertThatIllegalStateException().as("compile of id($0) with no parameter").isThrownBy(() -> compile(id(var(0)), 0));
  }
}
