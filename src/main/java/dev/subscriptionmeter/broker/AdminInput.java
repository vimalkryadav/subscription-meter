package dev.subscriptionmeter.broker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Strict validation of broker admin input. */
final class AdminInput {
    static final class Bad extends Exception { Bad(String message) { super(message); } }

    record KeyInput(String name, String purpose, String memberId, BigDecimal budget, Integer rpm, String models,
                    int maxOutputTokens, Instant expiresAt, boolean active) {
        Map<String, Object> describe() {
            var m = new LinkedHashMap<String, Object>();
            m.put("name", name); m.put("purpose", purpose); m.put("memberId", memberId); m.put("weeklyBudgetUsd", budget);
            m.put("rpm", rpm); m.put("allowedModels", models); m.put("maxOutputTokens", maxOutputTokens);
            m.put("expiresAt", expiresAt == null ? null : expiresAt.toString()); m.put("active", active);
            return m;
        }
    }

    private AdminInput() {}

    static int integer(String raw, int min, int max, String name) throws Bad {
        try { int v = Integer.parseInt(raw); if (v < min || v > max) throw new Bad("Invalid " + name); return v; }
        catch (NumberFormatException e) { throw new Bad("Invalid " + name); }
    }

    static BigDecimal money(JsonNode node, boolean required, String name) throws Bad {
        if (node.isMissingNode() || node.isNull()) { if (required) throw new Bad(name + " is required"); return null; }
        if (!node.isNumber()) throw new Bad("Invalid " + name);
        BigDecimal v = node.decimalValue();
        if (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(10_000)) > 0 || v.scale() > 6) throw new Bad("Invalid " + name);
        return v;
    }

    static BigDecimal rate(JsonNode node, boolean required, String name) throws Bad {
        BigDecimal v = money(node, required, name);
        if (v != null && v.compareTo(BigDecimal.valueOf(1000)) > 0) throw new Bad("Invalid " + name);
        return v;
    }

    private static String text(JsonNode node, String pattern, boolean required, String name) throws Bad {
        if (node.isMissingNode() || node.isNull() || (node.isString() && node.asString().isBlank())) {
            if (required) throw new Bad(name + " is required"); return null;
        }
        if (!node.isString() || !node.asString().strip().matches(pattern)) throw new Bad("Invalid " + name);
        return node.asString().strip();
    }

    static KeyInput key(JsonNode b, boolean creating) throws Bad {
        String name = text(b.path("name"), "[^\\p{Cntrl}]{1,100}", true, "name");
        String purpose = creating ? text(b.path("purpose"), "[a-z_]{1,30}", true, "purpose") : null;
        String member = creating ? text(b.path("memberId"), "[A-Za-z0-9-]{1,36}", false, "memberId") : null;
        BigDecimal budget = money(b.path("weeklyBudgetUsd"), false, "weeklyBudgetUsd");
        Integer rpm = b.path("rpm").isNull() || b.path("rpm").isMissingNode() ? null : integer(b.path("rpm").asString(""), 1, 10_000, "rpm");
        String models = null;
        if (b.path("allowedModels").isArray()) {
            var list = new ArrayList<String>();
            for (JsonNode m : b.path("allowedModels")) list.add(text(m, "[A-Za-z0-9._:-]{1,100}", true, "allowedModels"));
            if (list.size() > 50) throw new Bad("Too many allowedModels");
            models = list.isEmpty() ? null : String.join(",", list);
        } else if (!b.path("allowedModels").isMissingNode() && !b.path("allowedModels").isNull()) throw new Bad("Invalid allowedModels");
        int maxOutput = b.path("maxOutputTokens").isMissingNode() || b.path("maxOutputTokens").isNull() ? 16_384
                : integer(b.path("maxOutputTokens").asString(""), 1, 128_000, "maxOutputTokens");
        Instant expires = null;
        String rawExpiry = text(b.path("expiresAt"), "[0-9TZ:.+-]{10,40}", false, "expiresAt");
        if (rawExpiry != null) {
            try { expires = Instant.parse(rawExpiry); } catch (RuntimeException e) { throw new Bad("Invalid expiresAt"); }
        }
        boolean active = creating || !b.has("active") || b.path("active").asBoolean(true);
        return new KeyInput(name, purpose, member, budget, rpm, models, maxOutput, expires, active);
    }

    static BrokerStore.Price price(JsonNode b) throws Bad {
        String model = text(b.path("model"), "[A-Za-z0-9._:-]{1,100}", true, "model");
        Integer longTokens = b.path("longContextTokens").isMissingNode() || b.path("longContextTokens").isNull() ? null
                : integer(b.path("longContextTokens").asString(""), 1, 10_000_000, "longContextTokens");
        return new BrokerStore.Price(model, rate(b.path("inputUsdPerMTok"), true, "inputUsdPerMTok"),
                rate(b.path("cachedInputUsdPerMTok"), true, "cachedInputUsdPerMTok"), rate(b.path("outputUsdPerMTok"), true, "outputUsdPerMTok"),
                longTokens, rate(b.path("longInputUsdPerMTok"), false, "longInputUsdPerMTok"),
                rate(b.path("longCachedInputUsdPerMTok"), false, "longCachedInputUsdPerMTok"), rate(b.path("longOutputUsdPerMTok"), false, "longOutputUsdPerMTok"));
    }
}
