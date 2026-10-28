package io.github.marishasahay.agentic.app;

/** Minimal ANSI styling; disabled when {@code NO_COLOR} is set or there is no console. */
final class Ansi {

  private static final boolean ENABLED = System.getenv("NO_COLOR") == null
      && System.console() != null;

  private Ansi() {}

  static String bold(String s) {
    return wrap("1", s);
  }

  static String dim(String s) {
    return wrap("2", s);
  }

  static String red(String s) {
    return wrap("31", s);
  }

  static String green(String s) {
    return wrap("32", s);
  }

  static String yellow(String s) {
    return wrap("33", s);
  }

  static String blue(String s) {
    return wrap("34", s);
  }

  static String magenta(String s) {
    return wrap("35", s);
  }

  static String cyan(String s) {
    return wrap("36", s);
  }

  private static String wrap(String code, String s) {
    return ENABLED ? "\u001b[" + code + "m" + s + "\u001b[0m" : s;
  }
}
