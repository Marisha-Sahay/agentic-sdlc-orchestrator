package io.github.marishasahay.agentic.agents.llm;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders a contract record as a compact JSON skeleton for the prompt, e.g.
 * {@code {"title": "string", "tasks": [{"id": "string"}]}}. The model is told the exact shape
 * in plain text and asked for JSON mode; the contract is then enforced by parsing and gates.
 *
 * <p>Provider-side schema enforcement is deliberately not used: with Gemini 2.5 Flash, nested
 * response schemas made calls hang for minutes, while JSON mode with the shape in the prompt
 * returned a valid object in about 30 seconds (see ADR-0010).
 */
public final class JsonShape {

  private JsonShape() {}

  /**
   * Skeleton of a type.
   *
   * @param type record, list, enum, string, number or boolean type
   * @return JSON text where each value names its type
   */
  public static String of(Type type) {
    if (type instanceof ParameterizedType parameterized
        && parameterized.getRawType() instanceof Class<?> raw && List.class.isAssignableFrom(raw)) {
      return "[" + of(parameterized.getActualTypeArguments()[0]) + "]";
    }
    if (!(type instanceof Class<?> cls)) {
      return "\"string\"";
    }
    if (cls.isRecord()) {
      return Arrays.stream(cls.getRecordComponents())
          .map(c -> "\"" + c.getName() + "\": " + of(componentType(c)))
          .collect(Collectors.joining(", ", "{", "}"));
    }
    if (cls.isEnum()) {
      return Arrays.stream(cls.getEnumConstants()).map(Object::toString)
          .collect(Collectors.joining("|", "\"", "\""));
    }
    if (cls == boolean.class || cls == Boolean.class) {
      return "true";
    }
    if (Number.class.isAssignableFrom(cls) || (cls.isPrimitive() && cls != char.class)) {
      return "0";
    }
    return "\"string\"";
  }

  private static Type componentType(RecordComponent component) {
    return component.getGenericType();
  }
}
