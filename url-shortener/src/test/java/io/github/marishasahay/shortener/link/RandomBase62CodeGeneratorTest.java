package io.github.marishasahay.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RandomBase62CodeGeneratorTest {

  private final RandomBase62CodeGenerator generator =
      new RandomBase62CodeGenerator(new SecureRandom(), 7);

  @ParameterizedTest
  @ValueSource(ints = {1, 4, 7, 32})
  void shouldGenerateCodesOfConfiguredLength(int length) {
    RandomBase62CodeGenerator sized = new RandomBase62CodeGenerator(new SecureRandom(), length);

    assertThat(sized.generate()).hasSize(length);
  }

  @Test
  void shouldOnlyUseBase62Characters() {
    IntStream.range(0, 1_000).mapToObj(i -> generator.generate())
        .forEach(code -> assertThat(code).matches("^[0-9A-Za-z]{7}$"));
  }

  @Test
  void shouldEventuallyUseEveryCharacterOfTheAlphabet() {
    Set<Character> seen = new HashSet<>();
    IntStream.range(0, 10_000).mapToObj(i -> generator.generate())
        .forEach(code -> code.chars().forEach(c -> seen.add((char) c)));

    assertThat(seen).hasSize(RandomBase62CodeGenerator.ALPHABET.length());
  }

  @Test
  void shouldProduceDistinctCodes() {
    Set<String> codes = new HashSet<>();
    IntStream.range(0, 10_000).forEach(i -> codes.add(generator.generate()));

    assertThat(codes).hasSize(10_000);
  }

  @Test
  void shouldDeriveCodesFromTheRandomSource() {
    RandomBase62CodeGenerator first = new RandomBase62CodeGenerator(new Random(42), 7);
    RandomBase62CodeGenerator second = new RandomBase62CodeGenerator(new Random(42), 7);

    assertThat(first.generate()).isEqualTo(second.generate());
  }

  @Test
  void shouldRejectNonPositiveLength() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new RandomBase62CodeGenerator(new SecureRandom(), 0));
  }
}
