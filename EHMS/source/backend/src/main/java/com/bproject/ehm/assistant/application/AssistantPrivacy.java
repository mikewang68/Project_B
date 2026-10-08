package com.bproject.ehm.assistant.application;

import com.bproject.ehm.asset.application.DeviceView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Session-local aliases; originals and identity fields never accompany tool results. */
public class AssistantPrivacy {
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final Map<String, String> codes = new LinkedHashMap<>();
    public void register(List<DeviceView> devices) {
        for (DeviceView d : devices) {
            String alias = codes.computeIfAbsent(d.code(), x -> "DEV_" + String.format("%03d", codes.size() + 1));
            aliases.put(d.code(), alias);
            if (d.name() != null && !d.name().isBlank()) aliases.put(d.name(), alias);
            if (d.area() != null && !d.area().isBlank()) aliases.put(d.area(), "[作业区域]");
            if (d.owner() != null && !d.owner().isBlank()) aliases.put(d.owner(), "[责任岗位]");
        }
    }
    public String outgoing(String text) {
        String value = text == null ? "" : text;
        for (var entry : aliases.entrySet().stream().sorted((a,b) -> Integer.compare(b.getKey().length(), a.getKey().length())).toList())
            value = value.replace(entry.getKey(), entry.getValue());
        for (var entry : codes.entrySet()) value = value.replaceAll("(?i)"+java.util.regex.Pattern.quote(entry.getKey()), entry.getValue());
        return value.replaceAll("(?i)(?:https?://|jdbc:)[^\\s\"<>]+", "[地址已隐藏]")
                .replaceAll("(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?", "[IP已隐藏]")
                .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[电话已隐藏]")
                .replaceAll("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}", "[邮箱已隐藏]")
                .replaceAll("(?i)(?:sk-[A-Za-z0-9_-]+|Bearer\\s+[A-Za-z0-9._-]+)", "[密钥已隐藏]")
                .replaceAll("(?i)(password|api[_-]?key|secret|token|密码|口令)\\s*[:=：]\\s*[^\\s,;，；]+", "$1=[已隐藏]")
                .replaceAll("[A-Za-z]:[\\\\/][^\\s\"<>]+|/(?:home|data|opt|etc)/[^\\s\"<>]+", "[路径已隐藏]");
    }
    public String restore(String answer) {
        String value = answer == null ? "" : answer;
        for (var e : codes.entrySet()) value = value.replace(e.getValue(), e.getKey());
        return value;
    }
    public String resolveCode(String alias) {
        if (alias == null) return "";
        return codes.entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(alias))
                .map(Map.Entry::getKey).findFirst().orElse(alias.trim().toUpperCase());
    }
}
