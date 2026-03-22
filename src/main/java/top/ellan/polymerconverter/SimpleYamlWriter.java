package top.ellan.polymerconverter;

import java.util.List;
import java.util.Map;

public class SimpleYamlWriter {
    private SimpleYamlWriter() {
    }

    public static String dump(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        writeMap(sb, data, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeMap(StringBuilder sb, Map<String, Object> map, int indent) {
        String pad = " ".repeat(indent);

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }

            sb.append(pad).append(entry.getKey()).append(":");

            if (value instanceof Map<?, ?> m) {
                if (m.isEmpty()) {
                    sb.append(" {}\n");
                } else {
                    sb.append("\n");
                    writeMap(sb, (Map<String, Object>) m, indent + 2);
                }
            } else if (value instanceof List<?> l) {
                if (l.isEmpty()) {
                    sb.append(" []\n");
                } else {
                    sb.append("\n");
                    writeList(sb, l, indent);
                }
            } else {
                sb.append(' ').append(formatScalar(value)).append("\n");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeList(StringBuilder sb, List<?> list, int indent) {
        String pad = " ".repeat(indent);

        for (Object value : list) {
            sb.append(pad).append("-");

            if (value instanceof Map<?, ?> m) {
                if (m.isEmpty()) {
                    sb.append(" {}\n");
                } else {
                    sb.append("\n");
                    writeMap(sb, (Map<String, Object>) m, indent + 2);
                }
            } else if (value instanceof List<?> l) {
                if (l.isEmpty()) {
                    sb.append(" []\n");
                } else {
                    sb.append("\n");
                    writeList(sb, l, indent + 2);
                }
            } else {
                sb.append(' ').append(formatScalar(value)).append("\n");
            }
        }
    }

    private static String formatScalar(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }

        String text = String.valueOf(value);
        if (text.isEmpty()) {
            return "\"\"";
        }

        boolean quote = false;
        if (Character.isWhitespace(text.charAt(0)) || Character.isWhitespace(text.charAt(text.length() - 1))) {
            quote = true;
        }

        for (int i = 0; i < text.length() && !quote; i++) {
            char c = text.charAt(i);
            if (c == ':' || c == '#' || c == '[' || c == ']' || c == '{' || c == '}' || c == ',' || c == '\'' || c == '"' || c == '\n') {
                quote = true;
            }
        }

        if (text.equalsIgnoreCase("null") || text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
            quote = true;
        }

        if (!quote && looksNumeric(text)) {
            quote = true;
        }

        if (!quote) {
            return text;
        }

        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean looksNumeric(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
