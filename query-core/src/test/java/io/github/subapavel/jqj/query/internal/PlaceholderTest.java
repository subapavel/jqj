package io.github.subapavel.jqj.query.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlaceholderTest {
  @Test
  void maxReturnsTheLarger() {
    assertThat(Placeholder.max(3, 7)).isEqualTo(7);
    assertThat(Placeholder.max(7, 3)).isEqualTo(7);
  }

  @Test
  void maxOfEqualValues() {
    assertThat(Placeholder.max(5, 5)).isEqualTo(5);
  }
}
