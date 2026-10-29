package io.github.marishasahay.agentic.core.policy.rules;

import io.github.marishasahay.agentic.core.policy.PolicyFinding;
import io.github.marishasahay.agentic.core.policy.PolicyInput;
import io.github.marishasahay.agentic.core.policy.PolicyRule;
import io.github.marishasahay.agentic.core.policy.Severity;
import io.github.marishasahay.agentic.core.util.Globs;
import io.github.marishasahay.agentic.core.workspace.FileChange;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The build file is also where the gates live: test execution, the coverage threshold,
 * Checkstyle and their skip switches. Authorizing a dependency change must not implicitly allow
 * an agent to make a failing gate pass by weakening it, so any change to these controls is
 * blocked outright (a human changes them, in a reviewed commit, outside the run).
 *
 * <p>Protected: the configuration of the Checkstyle, JaCoCo, Surefire, Failsafe and Enforcer
 * plugins (in {@code build}, {@code pluginManagement} and every profile), properties that look
 * like skip flags or gate settings, and the source/test directories.
 */
public final class BuildIntegrityRule implements PolicyRule {

  static final Set<String> PROTECTED_PLUGINS = Set.of("maven-checkstyle-plugin",
      "jacoco-maven-plugin", "maven-surefire-plugin", "maven-failsafe-plugin",
      "maven-enforcer-plugin");
  private static final Pattern PROTECTED_PROPERTY = Pattern.compile(
      "(?i).*(skip|jacoco|checkstyle|surefire|failsafe|coverage|testfailureignore|maven\\.test)"
          + ".*");
  private static final Set<String> PROTECTED_BUILD_ELEMENTS = Set.of("sourceDirectory",
      "testSourceDirectory", "outputDirectory", "testOutputDirectory");
  private static final String BUILD_FILES = "**/pom.xml";

  @Override
  public String name() {
    return "build-integrity";
  }

  @Override
  public List<PolicyFinding> evaluate(PolicyInput input) {
    List<PolicyFinding> findings = new ArrayList<>();
    for (FileChange change : input.changes().changes()) {
      if (!Globs.matches(BUILD_FILES, change.path())) {
        continue;
      }
      Optional<String> before = input.base().read(change.path());
      if (change.operation() == FileChange.Operation.DELETE) {
        findings.add(block("deleting a build file removes every validation control",
            change.path()));
        continue;
      }
      if (before.isEmpty()) {
        continue;
      }
      Map<String, String> was = controls(before.get());
      Map<String, String> now;
      try {
        now = controls(change.content());
      } catch (IllegalArgumentException e) {
        findings.add(block("build file is not valid XML: " + e.getMessage(), change.path()));
        continue;
      }
      Set<String> changed = new TreeSet<>();
      was.forEach((key, value) -> {
        if (!value.equals(now.get(key))) {
          changed.add(key);
        }
      });
      now.keySet().stream().filter(key -> !was.containsKey(key)).forEach(changed::add);
      if (!changed.isEmpty()) {
        findings.add(block("validation controls in the build file are protected (tests, "
            + "coverage, Checkstyle, skip flags); changed: " + changed, change.path()));
      }
    }
    return findings;
  }

  /**
   * The protected controls of a POM, keyed by where they are declared.
   *
   * @param pom POM XML
   * @return control key to canonical value
   * @throws IllegalArgumentException if the XML cannot be parsed
   */
  static Map<String, String> controls(String pom) {
    Element project = parse(pom);
    Map<String, String> controls = new TreeMap<>();
    collect(project, "project", controls);
    for (Element profile : children(child(project, "profiles"), "profile")) {
      collect(profile, "profile:" + text(child(profile, "id")), controls);
    }
    return controls;
  }

  private static void collect(Element scope, String where, Map<String, String> controls) {
    for (Element property : children(child(scope, "properties"), null)) {
      if (PROTECTED_PROPERTY.matcher(property.getTagName()).matches()) {
        controls.put(where + "/property:" + property.getTagName(), text(property));
      }
    }
    Element build = child(scope, "build");
    for (Element element : children(build, null)) {
      if (PROTECTED_BUILD_ELEMENTS.contains(element.getTagName())) {
        controls.put(where + "/build:" + element.getTagName(), text(element));
      }
    }
    plugins(child(build, "plugins"), where + "/build", controls);
    plugins(child(child(build, "pluginManagement"), "plugins"), where + "/pluginManagement",
        controls);
  }

  private static void plugins(Element plugins, String where, Map<String, String> controls) {
    for (Element plugin : children(plugins, "plugin")) {
      String artifactId = text(child(plugin, "artifactId"));
      if (PROTECTED_PLUGINS.contains(artifactId)) {
        controls.put(where + "/plugin:" + artifactId, canonical(plugin));
      }
    }
  }

  private static Element parse(String xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      return factory.newDocumentBuilder()
          .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
          .getDocumentElement();
    } catch (Exception e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  private static Element child(Element parent, String tag) {
    return children(parent, tag).stream().findFirst().orElse(null);
  }

  private static List<Element> children(Element parent, String tag) {
    List<Element> result = new ArrayList<>();
    if (parent == null) {
      return result;
    }
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      if (nodes.item(i) instanceof Element e && (tag == null || e.getTagName().equals(tag))) {
        result.add(e);
      }
    }
    return result;
  }

  private static String text(Element element) {
    return element == null ? "" : element.getTextContent().strip();
  }

  /** Whitespace- and comment-insensitive rendering of an element tree. */
  private static String canonical(Element element) {
    StringBuilder out = new StringBuilder("<").append(element.getTagName());
    NamedNodeMap attributes = element.getAttributes();
    Map<String, String> sorted = new TreeMap<>();
    for (int i = 0; i < attributes.getLength(); i++) {
      sorted.put(attributes.item(i).getNodeName(), attributes.item(i).getNodeValue());
    }
    sorted.forEach((k, v) -> out.append(' ').append(k).append("=\"").append(v).append('"'));
    out.append('>');
    List<Element> children = children(element, null);
    if (children.isEmpty()) {
      NodeList nodes = element.getChildNodes();
      for (int i = 0; i < nodes.getLength(); i++) {
        if (nodes.item(i).getNodeType() == Node.TEXT_NODE
            || nodes.item(i).getNodeType() == Node.CDATA_SECTION_NODE) {
          out.append(nodes.item(i).getNodeValue().strip());
        }
      }
    } else {
      children.forEach(c -> out.append(canonical(c)));
    }
    return out.append("</").append(element.getTagName()).append('>').toString();
  }

  private PolicyFinding block(String message, String path) {
    return new PolicyFinding(name(), Severity.BLOCK, message, path);
  }
}
