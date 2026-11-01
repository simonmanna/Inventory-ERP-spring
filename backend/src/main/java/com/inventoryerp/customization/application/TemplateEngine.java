package com.inventoryerp.customization.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Logic-less template engine for print templates and automation messages (ADR-S029 §3): a Mustache subset —
 * {@code {{a.b}}} (HTML-escaped), {@code {{{raw}}}} / {@code {{& raw}}}, sections {@code {{#x}}…{{/x}}} over lists,
 * maps and truthy values, inverted sections {@code {{^x}}…{{/x}}}, {@code {{.}}} and comments {@code {{! …}}}. It never
 * executes code or reads files; nesting is capped at {@value #MAX_DEPTH} and output at {@value #MAX_OUTPUT} chars.
 */
public final class TemplateEngine {

    public static final int MAX_DEPTH = 10;
    public static final int MAX_OUTPUT = 2_000_000;

    /** A template that cannot be parsed or rendered; the message is shown to the user. */
    public static final class TemplateException extends RuntimeException {
        public TemplateException(String message) {
            super(message);
        }
    }

    private sealed interface Node permits Text, Var, Section {}

    private record Text(String text) implements Node {}

    private record Var(String name, boolean escape) implements Node {}

    private record Section(String name, boolean inverted, List<Node> children) implements Node {}

    private TemplateEngine() {}

    /** Render {@code template} against {@code context} as HTML ({@code {{x}}} is escaped). */
    public static String render(String template, Map<String, ?> context) {
        return render(template, context, true);
    }

    /** Render as plain text (e-mail bodies, webhook values): nothing is escaped. */
    public static String renderText(String template, Map<String, ?> context) {
        return render(template, context, false);
    }

    private static String render(String template, Map<String, ?> context, boolean html) {
        List<Node> nodes = parse(template == null ? "" : template);
        StringBuilder out = new StringBuilder();
        Deque<Object> stack = new ArrayDeque<>();
        stack.push(context == null ? Map.of() : context);
        renderNodes(nodes, stack, out, html);
        return out.toString();
    }

    /** Parse only (designer validation): throws {@link TemplateException} on an unbalanced template. */
    public static void validate(String template) {
        parse(template == null ? "" : template);
    }

    // ---- parsing --------------------------------------------------------------------------------------------------

    private static List<Node> parse(String t) {
        Deque<List<Node>> lists = new ArrayDeque<>();
        Deque<String[]> open = new ArrayDeque<>(); // {name, inverted}
        lists.push(new ArrayList<>());
        int i = 0;
        while (i < t.length()) {
            int start = t.indexOf("{{", i);
            if (start < 0) {
                lists.peek().add(new Text(t.substring(i)));
                break;
            }
            if (start > i) {
                lists.peek().add(new Text(t.substring(i, start)));
            }
            boolean triple = t.startsWith("{{{", start);
            int end = t.indexOf(triple ? "}}}" : "}}", start + (triple ? 3 : 2));
            if (end < 0) {
                throw new TemplateException("Unclosed tag at position " + start);
            }
            String tag = t.substring(start + (triple ? 3 : 2), end).trim();
            i = end + (triple ? 3 : 2);
            if (triple) {
                lists.peek().add(new Var(name(tag, start), false));
                continue;
            }
            if (tag.isEmpty()) {
                throw new TemplateException("Empty tag at position " + start);
            }
            char sigil = tag.charAt(0);
            switch (sigil) {
                case '!' -> {
                    // comment
                }
                case '#', '^' -> {
                    String name = name(tag.substring(1).trim(), start);
                    if (open.size() >= MAX_DEPTH) {
                        throw new TemplateException("Sections are nested deeper than " + MAX_DEPTH + " levels");
                    }
                    open.push(new String[] {name, sigil == '^' ? "1" : "0"});
                    lists.push(new ArrayList<>());
                }
                case '/' -> {
                    String name = name(tag.substring(1).trim(), start);
                    if (open.isEmpty()) {
                        throw new TemplateException("Unexpected closing tag {{/" + name + "}}");
                    }
                    String[] top = open.pop();
                    if (!top[0].equals(name)) {
                        throw new TemplateException("Section {{#" + top[0] + "}} is closed by {{/" + name + "}}");
                    }
                    List<Node> children = lists.pop();
                    lists.peek().add(new Section(name, "1".equals(top[1]), List.copyOf(children)));
                }
                case '&' -> lists.peek().add(new Var(name(tag.substring(1).trim(), start), false));
                case '>', '=' -> throw new TemplateException("Partials and delimiter changes are not supported");
                default -> lists.peek().add(new Var(name(tag, start), true));
            }
        }
        if (!open.isEmpty()) {
            throw new TemplateException("Unclosed section {{#" + open.peek()[0] + "}}");
        }
        return lists.pop();
    }

    private static String name(String raw, int at) {
        if (raw.isEmpty() || !(raw.equals(".") || raw.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*"))) {
            throw new TemplateException("Invalid tag name '" + raw + "' at position " + at);
        }
        return raw;
    }

    // ---- rendering ------------------------------------------------------------------------------------------------

    private static void renderNodes(List<Node> nodes, Deque<Object> stack, StringBuilder out, boolean html) {
        for (Node n : nodes) {
            switch (n) {
                case Text t -> append(out, t.text());
                case Var v -> {
                    Object value = lookup(stack, v.name());
                    String s = value == null ? "" : stringOf(value);
                    append(out, v.escape() && html ? escape(s) : s);
                }
                case Section s -> {
                    Object value = lookup(stack, s.name());
                    boolean truthy = truthy(value);
                    if (s.inverted()) {
                        if (!truthy) {
                            renderNodes(s.children(), stack, out, html);
                        }
                    } else if (truthy) {
                        if (value instanceof Collection<?> items) {
                            for (Object item : items) {
                                stack.push(item == null ? "" : item);
                                renderNodes(s.children(), stack, out, html);
                                stack.pop();
                            }
                        } else if (value instanceof Map<?, ?>) {
                            stack.push(value);
                            renderNodes(s.children(), stack, out, html);
                            stack.pop();
                        } else {
                            renderNodes(s.children(), stack, out, html);
                        }
                    }
                }
            }
        }
    }

    private static void append(StringBuilder out, String s) {
        if (out.length() + s.length() > MAX_OUTPUT) {
            throw new TemplateException("The rendered output is larger than " + MAX_OUTPUT + " characters");
        }
        out.append(s);
    }

    /** Dotted lookup: the first segment is resolved from the innermost context outwards. */
    static Object lookup(Deque<Object> stack, String name) {
        if (".".equals(name)) {
            return stack.peek();
        }
        String[] parts = name.split("\\.");
        for (Object frame : stack) {
            if (frame instanceof Map<?, ?> m && m.containsKey(parts[0])) {
                Object v = m.get(parts[0]);
                for (int i = 1; i < parts.length; i++) {
                    if (!(v instanceof Map<?, ?> inner)) {
                        return null;
                    }
                    v = inner.get(parts[i]);
                }
                return v;
            }
        }
        return null;
    }

    static boolean truthy(Object v) {
        if (v == null || Boolean.FALSE.equals(v)) {
            return false;
        }
        if (v instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return !(v instanceof String s) || !s.isEmpty();
    }

    private static String stringOf(Object v) {
        if (v instanceof java.math.BigDecimal d) {
            return d.toPlainString();
        }
        if (v instanceof Collection<?> c) {
            List<String> parts = new ArrayList<>();
            c.forEach(x -> parts.add(x == null ? "" : stringOf(x)));
            return String.join(", ", parts);
        }
        return String.valueOf(v);
    }

    /** HTML escape of the five significant characters. */
    public static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
