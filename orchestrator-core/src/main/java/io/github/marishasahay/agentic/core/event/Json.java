package io.github.marishasahay.agentic.core.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Shared, canonical JSON configuration (sorted map keys keep hashes stable). */
public final class Json {

  public static final ObjectMapper MAPPER = JsonMapper.builder()
      .addModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .build();

  private Json() {}

  /**
   * Serialises a value as compact JSON.
   *
   * @param value value
   * @return JSON text
   */
  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialise " + value.getClass().getSimpleName(), e);
    }
  }

  /**
   * Parses JSON into a type.
   *
   * @param json JSON text
   * @param type target type
   * @param <T> target type
   * @return parsed value
   */
  public static <T> T read(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid JSON for " + type.getSimpleName() + ": "
          + e.getOriginalMessage(), e);
    }
  }
}
