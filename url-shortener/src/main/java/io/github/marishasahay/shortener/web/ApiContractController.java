package io.github.marishasahay.shortener.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the hand-written API contract for Swagger UI ({@code /swagger-ui.html}) and API
 * clients. The build copies {@code api/openapi.yaml} onto the classpath unchanged, so the UI
 * shows the same document the API compatibility gate enforces. A controller rather than a
 * static resource because neither Spring nor Tomcat maps {@code .yaml} to a media type.
 */
@RestController
public class ApiContractController {

  public static final String PATH = "/openapi.yaml";

  private final Resource contract = new ClassPathResource("api/openapi.yaml");

  /**
   * Returns the OpenAPI contract.
   *
   * @return the contract as {@code application/yaml}
   */
  @GetMapping(PATH)
  public ResponseEntity<Resource> contract() {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_YAML).body(contract);
  }
}
