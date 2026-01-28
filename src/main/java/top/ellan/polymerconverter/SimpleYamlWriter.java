package top.ellan.polymerconverter;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class SimpleYamlWriter {
    
    // 需要转义的特殊字符正则
    private static final Pattern SPECIAL_CHARS = Pattern.compile("[#:\\[\\]{},\"'\\n]|$\\s|\\s^");

    public static String dump(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        dumpMap(sb, data, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void dumpMap(StringBuilder sb, Map<String, Object> map, int indent) {
        String spaces = " ".repeat(indent);
        
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // 忽略空值，保持配置整洁
            if (value == null) continue;

            sb.append(spaces).append(key).append(":");

            if (value instanceof Map) {
                Map<String, Object> subMap = (Map<String, Object>) value;
                if (subMap.isEmpty()) {
                    sb.append(" {}\n");
                } else {
                    sb.append("\n");
                    dumpMap(sb, subMap, indent + 2);
                }
            } else if (value instanceof List) {
                List<?> list = (List<?>) value;
                if (list.isEmpty()) {
                    sb.append(" []\n");
                } else {
                    sb.append("\n");
                    dumpList(sb, list, indent);
                }
            } else {
                sb.append(" ").append(formatValue(value)).append("\n");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void dumpList(StringBuilder sb, List<?> list, int indent) {
        String spaces = " ".repeat(indent);
        
        for (Object item : list) {
            sb.append(spaces).append("-");
            
            if (item instanceof Map) {
                // 处理 List 中的 Map (关键修复)
                // 采用换行缩进风格，这是最不容易出错的 YAML 写法
                // - 
                //   key: value
                sb.append("\n");
                dumpMap(sb, (Map<String, Object>) item, indent + 2);
            } else if (item instanceof List) {
                sb.append("\n");
                dumpList(sb, (List<?>) item, indent + 2);
            } else {
                sb.append(" ").append(formatValue(item)).append("\n");
            }
        }
    }

    private static String formatValue(Object value) {
        if (value == null) return "null";
        
        String str = value.toString();
        
        // 如果是数字或布尔值，直接返回
        if (value instanceof Number || value instanceof Boolean) {
            return str;
        }

        // 字符串转义逻辑
        // 1. 如果包含换行，或是特殊符号，或者是数字格式的字符串，需要引号
        // 2. CraftEngine 使用 <!i> 作为非斜体标记，包含 < >，建议引号包裹
        boolean needsQuote = SPECIAL_CHARS.matcher(str).find() 
                || str.contains("<") 
                || str.contains(">")
                || isNumeric(str) // 防止 "123" 被识别为数字 123
                || str.equalsIgnoreCase("true") // 防止 "true" 字符串被识别为布尔
                || str.equalsIgnoreCase("false")
                || str.isEmpty();

        if (needsQuote) {
            // 简单转义双引号
            return "\"" + str.replace("\"", "\\\"") + "\"";
        }
        
        return str;
    }
    
    private static boolean isNumeric(String str) {
        try {
            Double.parseDouble(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}