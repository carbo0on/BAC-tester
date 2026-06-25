package engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

/**
 * A locator + transformation for a single dynamic value in a captured request
 * (CSRF token, nonce, anti-forgery header, per-session id, …).
 *
 * Before a captured request is replayed under a different identity, each
 * dynamic field is rewritten so the request is not rejected for a stale token
 * (a classic source of {@code BAC} false-negatives — see spec §7).
 *
 * <p>Persisted as a JSON array in {@code test_cases.dynamic_fields}.</p>
 */
public record DynamicField(String name, Location location, Strategy strategy, String value) {

    /** Where in the request the value lives. */
    public enum Location {
        QUERY_PARAM,   // ?name=value
        BODY_PARAM,    // form / body parameter
        COOKIE,        // Cookie header entry
        HEADER         // arbitrary request header
    }

    /** How to obtain the replacement value when the request is replayed. */
    public enum Strategy {
        REMOVE,        // strip the field entirely
        STATIC,        // replace with the literal {@link #value}
        FROM_COOKIE,   // copy from the running account's cookie named {@link #value}
        FROM_HEADER    // copy from the running account's header named {@link #value}
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public DynamicField {
        if (location == null) location = Location.BODY_PARAM;
        if (strategy == null) strategy = Strategy.REMOVE;
    }

    /** Parse a JSON array of fields; returns an empty list on null/blank/invalid. */
    public static List<DynamicField> parse(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<DynamicField> list = GSON.fromJson(json, new TypeToken<List<DynamicField>>() {}.getType());
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Serialize a list of fields to a JSON array string. */
    public static String toJson(List<DynamicField> fields) {
        return GSON.toJson(fields != null ? fields : new ArrayList<>());
    }

    /** Short human-readable summary for list rows / tooltips. */
    public String describe() {
        String act = switch (strategy) {
            case REMOVE      -> "remove";
            case STATIC      -> "set \"" + (value != null ? value : "") + "\"";
            case FROM_COOKIE -> "← cookie[" + value + "]";
            case FROM_HEADER -> "← header[" + value + "]";
        };
        return location + " " + name + " : " + act;
    }
}
