package io.github.marishasahay.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Links the running service to the published contract: every documented operation is served,
 * every API route is documented, and real responses carry the properties the contract marks as
 * required. Complements the orchestrator's structural compatibility gate, which compares
 * contract versions but cannot see behaviour.
 */
class ContractConformanceIT extends IntegrationTestSupport {

  private static final Set<String> METHODS = Set.of("get", "post", "put", "delete", "patch");

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;

  @SuppressWarnings("unchecked")
  private static Map<String, Object> contract() throws IOException {
    return new Yaml().load(Files.readString(Path.of("api", "openapi.yaml")));
  }

  @SuppressWarnings("unchecked")
  private static List<String> required(String schema) throws IOException {
    Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) contract()
        .get("components")).get("schemas");
    return (List<String>) ((Map<String, Object>) schemas.get(schema)).get("required");
  }

  @Test
  @SuppressWarnings("unchecked")
  void everyDocumentedOperationIsServedAndEveryApiRouteIsDocumented() throws Exception {
    Set<String> documented = new HashSet<>();
    ((Map<String, Map<String, Object>>) contract().get("paths")).forEach((path, item) ->
        item.keySet().stream().filter(METHODS::contains)
            .forEach(method -> documented.add(method.toUpperCase() + " " + path)));
    Set<String> served = new HashSet<>();
    mappings.getHandlerMethods().keySet().forEach(info -> info.getMethodsCondition().getMethods()
        .forEach(method -> info.getPathPatternsCondition().getPatternValues()
            .forEach(pattern -> served.add(method + " "
                + pattern.replaceAll("\\{(\\w+):[^/]*}", "{$1}")))));

    assertThat(served).containsAll(documented);
    assertThat(served).filteredOn(op -> op.contains(" /api/")).isSubsetOf(documented);
  }

  @Test
  void responsesCarryEveryPropertyTheContractRequires() throws Exception {
    String alias = "k-" + UUID.randomUUID().toString().substring(0, 12);
    MvcResult created = postLink("{\"url\": \"https://example.com/x\", \"customAlias\": \""
        + alias + "\"}").andReturn();
    MvcResult link = mockMvc.perform(get("/api/v1/links/" + alias)).andReturn();
    MvcResult stats = mockMvc.perform(get("/api/v1/links/" + alias + "/stats")).andReturn();
    MvcResult missing = mockMvc.perform(get("/api/v1/links/nope-" + alias)).andReturn();

    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertRequired(created, "LinkResponse");
    assertRequired(link, "LinkResponse");
    assertRequired(stats, "LinkStats");
    assertRequired(stats, "DailyClicks", "$.daily[0]");
    assertThat(missing.getResponse().getContentType()).isEqualTo("application/problem+json");
    assertRequired(missing, "ProblemDetail");
  }

  private static void assertRequired(MvcResult result, String schema) throws Exception {
    assertRequired(result, schema, "$");
  }

  private static void assertRequired(MvcResult result, String schema, String path)
      throws Exception {
    Map<String, Object> body = JsonPath.read(result.getResponse().getContentAsString(), path);
    assertThat(body).as(schema).containsKeys(required(schema).toArray(String[]::new));
  }
}
