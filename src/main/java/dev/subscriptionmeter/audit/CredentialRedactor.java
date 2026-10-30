package dev.subscriptionmeter.audit;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Defense in depth for stored copies only; forwarded traffic is never changed. */
@Component
public class CredentialRedactor {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern SENSITIVE = Pattern.compile("(?i).*(authorization|cookie|api[-_]?key|password|passwd|secret|access[-_]?token|refresh[-_]?token|id[-_]?token|credential).*|(?i)^token$");
    private static final Pattern KEYS = Pattern.compile("(?i)(?:sk-ant-[a-z0-9_-]{12,}|sm_[a-z0-9_-]{30,}|gh[pousr]_[a-z0-9]{20,}|github_pat_[a-z0-9_]{20,}|AKIA[A-Z0-9]{16}|eyJ[a-z0-9_-]+\\.[a-z0-9_-]+\\.[a-z0-9_-]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[a-z0-9._~+/-]+=*");
    private static final Pattern ASSIGNMENT = Pattern.compile("(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)[\\\"']?\\s*[=:]\\s*[\\\"']?)([^\\s\\\"',;}]+)");
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----");
    public boolean sensitive(String key) { return SENSITIVE.matcher(key).matches(); }
    public String text(String value) {
        if (value == null) return "";
        String result = PRIVATE_KEY.matcher(value).replaceAll("[REDACTED PRIVATE KEY]");
        result = KEYS.matcher(result).replaceAll("[REDACTED]");
        result = BEARER.matcher(result).replaceAll("Bearer [REDACTED]");
        return ASSIGNMENT.matcher(result).replaceAll("$1[REDACTED]");
    }
    public String payload(String value) {
        try { return JSON.writeValueAsString(clean(JSON.readTree(value))); }
        catch (RuntimeException e) { return text(value); }
    }
    private Object clean(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            Map<String,Object> out = new LinkedHashMap<>();
            for (var entry : node.properties()) out.put(entry.getKey(), sensitive(entry.getKey()) ? "[REDACTED]" : clean(entry.getValue()));
            return out;
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>(); node.forEach(n -> out.add(clean(n))); return out;
        }
        if (node.isString()) return text(node.asString());
        return JSON.readValue(node.toString(), Object.class);
    }
    public String headers(Map<String, List<String>> headers) {
        Map<String, Object> out = new TreeMap<>();
        headers.forEach((name, values) -> {
            // Authentication values are excluded, including custom token/key/cookie headers.
            if (!sensitive(name) && !name.toLowerCase(Locale.ROOT).contains("token"))
                out.put(name.toLowerCase(Locale.ROOT), values.stream().map(this::text).toList());
        });
        return JSON.writeValueAsString(out);
    }
    public String query(String query) {
        if (query == null) return "";
        List<String> pairs = new ArrayList<>();
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            try {
                String key = java.net.URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8);
                String value = parts.length < 2 ? "" : java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8);
                pairs.add(key + "=" + (sensitive(key) ? "[REDACTED]" : text(value)));
            } catch (IllegalArgumentException e) { pairs.add("[INVALID QUERY ENCODING]"); }
        }
        return String.join("&", pairs);
    }
}
