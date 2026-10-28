package io.github.marishasahay.agentic.agents.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Builds a {@link CodebaseIndex} with JavaParser. No model involved: same input, same index. */
public final class CodebaseIndexer {

  private static final Set<String> SKIPPED = Set.of("target", ".git", "node_modules", ".idea");
  private static final Map<String, String> MAPPINGS = Map.of(
      "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
      "DeleteMapping", "DELETE", "PatchMapping", "PATCH", "RequestMapping", "ANY");

  private final JavaParser parser = new JavaParser(new ParserConfiguration()
      .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

  /**
   * Indexes a project directory.
   *
   * @param root project root
   * @return the index
   */
  public CodebaseIndex index(Path root) {
    List<CodebaseIndex.SourceType> sources = new ArrayList<>();
    List<CodebaseIndex.Endpoint> endpoints = new ArrayList<>();
    List<String> migrations = new ArrayList<>();
    List<String> resources = new ArrayList<>();
    List<String> tests = new ArrayList<>();
    for (Path file : files(root)) {
      String rel = root.relativize(file).toString().replace('\\', '/');
      if (rel.startsWith("src/test/")) {
        tests.add(rel);
      } else if (rel.startsWith("src/main/java/") && rel.endsWith(".java")) {
        parse(file).ifPresent(cu -> cu.getTypes().forEach(t -> {
          sources.add(describe(rel, t));
          endpoints.addAll(endpointsOf(t));
        }));
      } else if (rel.contains("/db/migration/")) {
        migrations.add(rel);
      } else if (rel.startsWith("src/main/resources/") || rel.startsWith("api/")) {
        resources.add(rel);
      }
    }
    return new CodebaseIndex(sources, endpoints, migrations, resources, tests);
  }

  private Optional<CompilationUnit> parse(Path file) {
    try {
      ParseResult<CompilationUnit> result = parser.parse(file);
      return result.getResult();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static CodebaseIndex.SourceType describe(String path, TypeDeclaration<?> type) {
    String kind = type instanceof RecordDeclaration ? "record"
        : type.isEnumDeclaration() ? "enum"
        : type.isClassOrInterfaceDeclaration()
            && type.asClassOrInterfaceDeclaration().isInterface() ? "interface" : "class";
    List<String> dependencies = type.getMembers().stream()
        .filter(m -> m instanceof ConstructorDeclaration)
        .map(m -> (ConstructorDeclaration) m)
        .filter(c -> c.hasModifier(Modifier.Keyword.PUBLIC) || type.getConstructors().size() == 1)
        .flatMap(c -> c.getParameters().stream().map(p -> p.getType().asString()))
        .distinct().toList();
    List<String> methods = type.getMethods().stream()
        .filter(m -> m.hasModifier(Modifier.Keyword.PUBLIC) || isInterface(type))
        .map(CodebaseIndexer::signature).toList();
    int lines = type.getRange().map(r -> r.end.line).orElse(0);
    return new CodebaseIndex.SourceType(path, type.getNameAsString(), kind, roleOf(type),
        dependencies, methods, lines);
  }

  private static boolean isInterface(TypeDeclaration<?> type) {
    return type.isClassOrInterfaceDeclaration() && type.asClassOrInterfaceDeclaration()
        .isInterface();
  }

  private static String signature(MethodDeclaration m) {
    return m.getType().asString() + " " + m.getNameAsString() + "("
        + String.join(", ", m.getParameters().stream().map(p -> p.getType().asString()).toList())
        + ")";
  }

  private static String roleOf(TypeDeclaration<?> type) {
    Set<String> annotations = Set.copyOf(type.getAnnotations().stream()
        .map(AnnotationExpr::getNameAsString).toList());
    if (annotations.contains("RestController") || annotations.contains("Controller")) {
      return "controller";
    }
    if (annotations.contains("RestControllerAdvice") || annotations.contains("ControllerAdvice")) {
      return "web";
    }
    if (annotations.contains("Service")) {
      return "service";
    }
    if (annotations.contains("Repository")) {
      return "repository";
    }
    if (annotations.contains("Configuration") || annotations.contains("ConfigurationProperties")
        || annotations.contains("SpringBootApplication")) {
      return "config";
    }
    String text = type.toString();
    if (text.contains("OncePerRequestFilter") || text.contains("implements Filter")
        || text.contains("HandlerInterceptor")) {
      return "filter";
    }
    if (annotations.contains("Component")) {
      return "component";
    }
    return type instanceof RecordDeclaration || type.isEnumDeclaration() ? "domain" : "other";
  }

  private static List<CodebaseIndex.Endpoint> endpointsOf(TypeDeclaration<?> type) {
    String base = type.getAnnotationByName("RequestMapping").map(a -> pathOf(a, type))
        .orElse("");
    List<CodebaseIndex.Endpoint> endpoints = new ArrayList<>();
    for (MethodDeclaration method : type.getMethods()) {
      for (AnnotationExpr annotation : method.getAnnotations()) {
        String httpMethod = MAPPINGS.get(annotation.getNameAsString());
        if (httpMethod != null) {
          endpoints.add(new CodebaseIndex.Endpoint(httpMethod, base + pathOf(annotation, type),
              type.getNameAsString() + "#" + method.getNameAsString()));
        }
      }
    }
    return endpoints;
  }

  private static String pathOf(AnnotationExpr annotation, TypeDeclaration<?> type) {
    Expression value = null;
    if (annotation instanceof SingleMemberAnnotationExpr single) {
      value = single.getMemberValue();
    } else if (annotation instanceof NormalAnnotationExpr normal) {
      value = normal.getPairs().stream()
          .filter(p -> p.getNameAsString().equals("value") || p.getNameAsString().equals("path"))
          .map(p -> p.getValue()).findFirst().orElse(null);
    }
    if (value instanceof ArrayInitializerExpr array && !array.getValues().isEmpty()) {
      value = array.getValues().get(0);
    }
    return resolve(value, type);
  }

  /**
   * Resolves the string value of a mapping: literals, constants declared in the same type
   * (simple or qualified reference) and concatenations of those, which covers the idiomatic
   * {@code @RequestMapping(BASE_PATH)} style.
   */
  private static String resolve(Expression value, TypeDeclaration<?> type) {
    if (value instanceof StringLiteralExpr literal) {
      return literal.asString();
    }
    if (value instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
      return resolve(binary.getLeft(), type) + resolve(binary.getRight(), type);
    }
    String name = value instanceof NameExpr n ? n.getNameAsString()
        : value instanceof FieldAccessExpr f ? f.getNameAsString() : null;
    if (name == null) {
      return "";
    }
    return type.getFields().stream()
        .flatMap(f -> f.getVariables().stream())
        .filter(v -> v.getNameAsString().equals(name))
        .findFirst()
        .flatMap(v -> v.getInitializer())
        .map(init -> resolve(init, type))
        .orElse("");
  }

  private static List<Path> files(Path root) {
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> root.relativize(p).toString().isEmpty()
              || SKIPPED.stream().noneMatch(s -> root.relativize(p).startsWith(s)))
          .sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
