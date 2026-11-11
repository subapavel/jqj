package io.github.subapavel.jqj.query.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlaceholderTest {
  @Test
  void twiceDoubles() {
    assertThat(Placeholder.twice(3)).isEqualTo(6);
  }

  @Test
  void isNegativeAtTheBoundary() {
    assertThat(Placeholder.isNegative(-1)).isTrue();
    assertThat(Placeholder.isNegative(0)).isFalse();
    assertThat(Placeholder.isNegative(1)).isFalse();
  }

  @Test
  void twiceOfZeroAndNegative() {
    assertThat(Placeholder.twice(0)).isZero();
    assertThat(Placeholder.twice(-4)).isEqualTo(-8);
  }
}
