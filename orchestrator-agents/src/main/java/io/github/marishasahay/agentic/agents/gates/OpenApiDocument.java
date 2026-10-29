package io.github.marishasahay.agentic.agents.gates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Minimal OpenAPI reader: version, operations, and the parts of each operation a structural
 * backward-compatibility check needs (success responses, JSON request and response schemas,
 * with local {@code $ref}s resolved). Deliberately not a full OpenAPI toolchain.
 *
 * @param version the {@code openapi} field
 * @param operations every path + method
 * @param root the parsed document
 */
public record OpenApiDocument(String version, List<Operation> operations, JsonNode root) {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch",
      "head", "options");

  /**
   * One operation.
   *
   * @param method upper-case HTTP method
   * @param path path template
   * @param summary summary or operationId
   * @param node the operation object
   */
  public record Operation(String method, String path, String summary, JsonNode node) {
    public String signature() {
      return method + " " + path;
    }
  }

  /**
   * Parses YAML or JSON.
   *
   * @param text contract text
   * @return parsed document
   * @throws IllegalArgumentException if it is not a usable OpenAPI 3 document
   */
  public static OpenApiDocument parse(String text) {
    JsonNode root;
    try {
      root = YAML.readTree(text == null ? "" : text);
    } catch (IOException e) {
      throw new IllegalArgumentException("contract is not valid YAML: " + e.getMessage(), e);
    }
    if (root == null || !root.path("openapi").asText("").startsWith("3.")) {
      throw new IllegalArgumentException("contract must declare openapi: 3.x");
    }
    List<Operation> operations = new ArrayList<>();
    root.path("paths").properties().forEach(path -> path.getValue().properties().forEach(op -> {
      if (METHODS.contains(op.getKey())) {
        JsonNode node = op.getValue();
        String summary = node.path("summary").asText(node.path("operationId").asText(""));
        operations.add(new Operation(op.getKey().toUpperCase(), path.getKey(), summary, node));
      }
    }));
    if (operations.isEmpty()) {
      throw new IllegalArgumentException("contract defines no operations");
    }
    return new OpenApiDocument(root.path("openapi").asText(), operations, root);
  }

  /**
   * Finds an operation.
   *
   * @param signature {@code METHOD /path}
   * @return the operation, or null
   */
  public Operation operation(String signature) {
    return operations.stream().filter(o -> o.signature().equals(signature)).findFirst()
        .orElse(null);
  }

  /**
   * Documented 2xx and 3xx status codes of an operation.
   *
   * @param operation operation
   * @return status codes
   */
  public Set<String> successStatuses(Operation operation) {
    Set<String> statuses = new TreeSet<>();
    operation.node().path("responses").fieldNames().forEachRemaining(code -> {
      if (code.startsWith("2") || code.startsWith("3")) {
        statuses.add(code);
      }
    });
    return statuses;
  }

  /**
   * The JSON request body schema, resolved.
   *
   * @param operation operation
   * @return schema node, missing if none
   */
  public JsonNode requestSchema(Operation operation) {
    return resolve(resolve(operation.node().path("requestBody"))
        .path("content").path("application/json").path("schema"));
  }

  /**
   * The JSON response schema for a status, resolved.
   *
   * @param operation operation
   * @param status status code
   * @return schema node, missing if none
   */
  public JsonNode responseSchema(Operation operation, String status) {
    return resolve(resolve(operation.node().path("responses").path(status))
        .path("content").path("application/json").path("schema"));
  }

  /**
   * Property names of an object schema.
   *
   * @param schema resolved schema
   * @return property names
   */
  public static Set<String> properties(JsonNode schema) {
    Set<String> names = new LinkedHashSet<>();
    schema.path("properties").fieldNames().forEachRemaining(names::add);
    return names;
  }

  /**
   * Required property names of an object schema.
   *
   * @param schema resolved schema
   * @return required names
   */
  public static Set<String> required(JsonNode schema) {
    Set<String> names = new LinkedHashSet<>();
    schema.path("required").forEach(n -> names.add(n.asText()));
    return names;
  }

  /** Follows a local {@code $ref} (one level is enough for this document style). */
  private JsonNode resolve(JsonNode node) {
    JsonNode current = node;
    for (int depth = 0; depth < 5 && current.has("$ref"); depth++) {
      String ref = current.get("$ref").asText();
      if (!ref.startsWith("#/")) {
        return current;
      }
      current = root.at(ref.substring(1));
    }
    return current;
  }
}
