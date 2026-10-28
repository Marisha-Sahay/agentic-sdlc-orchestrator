package io.github.marishasahay.agentic.agents.gates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Minimal OpenAPI reader: version and operations. Enough to validate a contract and detect the
 * most damaging class of breaking change (a removed operation) without a full OpenAPI toolchain.
 *
 * @param version the {@code openapi} field
 * @param operations every path + method
 */
public record OpenApiDocument(String version, List<Operation> operations) {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch",
      "head", "options");

  /**
   * One operation.
   *
   * @param method upper-case HTTP method
   * @param path path template
   * @param summary summary or operationId
   */
  public record Operation(String method, String path, String summary) {
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
        operations.add(new Operation(op.getKey().toUpperCase(), path.getKey(), summary));
      }
    }));
    if (operations.isEmpty()) {
      throw new IllegalArgumentException("contract defines no operations");
    }
    return new OpenApiDocument(root.path("openapi").asText(), operations);
  }
}
