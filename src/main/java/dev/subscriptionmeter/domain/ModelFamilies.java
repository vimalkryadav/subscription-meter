package dev.subscriptionmeter.domain;

import java.util.Locale;

/** Recognized requested model identifiers; never infer a family from free-form display text. */
public final class ModelFamilies {
    private ModelFamilies() {}
    public static boolean isFable(String model) {
        if (model == null) return false;
        String name = model.toLowerCase(Locale.ROOT);
        return name.equals("fable") || name.startsWith("claude-fable-");
    }
}
