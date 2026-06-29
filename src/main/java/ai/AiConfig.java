package ai;

import db.DatabaseManager;

/**
 * Immutable snapshot of the AI auto-organization settings, loaded fresh from the
 * settings table before each use so changes take effect without reloading the
 * extension. All keys default safely (disabled) if absent.
 */
public record AiConfig(
        boolean enabled,
        boolean autoOrganize,
        String provider,     // GEMINI / GROQ / OPENROUTER
        String apiKey,
        String model,        // blank → provider default
        int maxChars) {

    public static AiConfig load(DatabaseManager db) {
        boolean enabled      = !"false".equalsIgnoreCase(get(db, "ai_enabled", "false"));
        boolean autoOrganize = !"false".equalsIgnoreCase(get(db, "ai_auto_organize", "true"));
        String provider      = get(db, "ai_provider", "GEMINI").trim().toUpperCase();
        String apiKey        = get(db, "ai_api_key", "");
        String model         = get(db, "ai_model", "").trim();
        int maxChars         = parseInt(get(db, "ai_max_chars", "1800"), 1800);
        return new AiConfig(enabled, autoOrganize, provider, apiKey, model, maxChars);
    }

    /** True when AI can actually be called (enabled + a key is present). */
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /** Returns the configured model or the provider's sensible default. */
    public String effectiveModel() {
        if (model != null && !model.isBlank()) return model;
        return switch (provider) {
            case "GROQ"       -> "llama-3.1-8b-instant";
            case "OPENROUTER" -> "meta-llama/llama-3.1-8b-instruct";
            default           -> "gemini-2.0-flash";   // GEMINI
        };
    }

    private static String get(DatabaseManager db, String key, String def) {
        try {
            String v = db.getSetting(key);
            return v != null ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}
