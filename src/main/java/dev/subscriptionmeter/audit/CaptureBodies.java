package dev.subscriptionmeter.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Splits a captured (already redacted) request body into pieces that repeat across turns, and rebuilds it.
 *
 * <p>Claude Code resends system, tools and every earlier message on each turn. Each of those becomes one piece,
 * stored once per member; a small manifest lists the pieces in order. {@code cache_control} markers move to the
 * newest messages every turn, so they are lifted out of message pieces and recorded in the manifest.
 * A body that is not a JSON object becomes one whole piece.
 *
 * <p>Callers must compare {@link #rebuild} with the original before relying on a manifest.
 */
public final class CaptureBodies {
    public static final int MANIFEST_VERSION = 1;
    /** Same defaults as {@link CredentialRedactor}, which serialized the stored body. */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> SINGLE = Set.of("system", "tools");
    private static final Set<String> LISTS = Set.of("messages", "input");
    private static final String MARKER = "cache_control";

    public record Piece(String key, String data) {
        public int chars() { return data.codePointCount(0, data.length()); }
    }
    public record Split(String manifest, List<Piece> pieces, int chars) { }

    private CaptureBodies() { }

    public static String key(String memberId, String data) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(memberId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Split split(String memberId, String body) {
        Map<String, Piece> pieces = new LinkedHashMap<>();
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("v", MANIFEST_VERSION);
        JsonNode root = parse(body);
        if (root == null || !root.isObject()) {
            manifest.put("whole", add(pieces, memberId, body));
        } else {
            ArrayNode order = manifest.putArray("order");
            ObjectNode env = manifest.putObject("env");
            ObjectNode refs = manifest.putObject("refs");
            ArrayNode cache = manifest.putArray("cache");
            for (var entry : root.properties()) {
                String name = entry.getKey();
                JsonNode value = entry.getValue();
                order.add(name);
                if (SINGLE.contains(name)) {
                    refs.put(name, add(pieces, memberId, JSON.writeValueAsString(value)));
                } else if (LISTS.contains(name) && value.isArray()) {
                    ArrayNode keys = refs.putArray(name);
                    for (int i = 0; i < value.size(); i++) {
                        JsonNode element = value.get(i).deepCopy();
                        lift(element, name, i, JSON.createArrayNode(), cache);
                        keys.add(add(pieces, memberId, JSON.writeValueAsString(element)));
                    }
                } else {
                    env.set(name, value);
                }
            }
        }
        return new Split(JSON.writeValueAsString(manifest), List.copyOf(pieces.values()),
                body.codePointCount(0, body.length()));
    }

    /** Keys a manifest refers to, in first-use order. */
    public static List<String> keys(String manifestJson) {
        JsonNode manifest = JSON.readTree(manifestJson);
        Set<String> keys = new LinkedHashSet<>();
        if (manifest.has("whole")) keys.add(manifest.get("whole").asString());
        JsonNode refs = manifest.get("refs");
        if (refs != null) {
            for (var entry : refs.properties()) {
                if (entry.getValue().isArray()) entry.getValue().forEach(k -> keys.add(k.asString()));
                else keys.add(entry.getValue().asString());
            }
        }
        return List.copyOf(keys);
    }

    public static String rebuild(String manifestJson, Map<String, String> pieces) {
        JsonNode manifest = JSON.readTree(manifestJson);
        if (manifest.path("v").asInt() != MANIFEST_VERSION) throw new IllegalStateException("Unsupported body manifest");
        if (manifest.has("whole")) return piece(pieces, manifest.get("whole").asString());
        ObjectNode out = JSON.createObjectNode();
        JsonNode env = manifest.get("env"), refs = manifest.get("refs");
        for (JsonNode nameNode : manifest.get("order")) {
            String name = nameNode.asString();
            JsonNode ref = refs.get(name);
            if (ref == null) {
                out.set(name, env.get(name));
            } else if (ref.isArray()) {
                ArrayNode list = out.putArray(name);
                for (JsonNode key : ref) list.add(JSON.readTree(piece(pieces, key.asString())));
            } else {
                out.set(name, JSON.readTree(piece(pieces, ref.asString())));
            }
        }
        for (JsonNode marker : manifest.get("cache")) {
            JsonNode target = out.get(marker.get("f").asString()).get(marker.get("i").asInt());
            for (JsonNode step : marker.get("p")) target = step.isInt() ? target.get(step.asInt()) : target.get(step.asString());
            insert((ObjectNode) target, marker.get("at").asInt(), marker.get("v"));
        }
        return JSON.writeValueAsString(out);
    }

    private static JsonNode parse(String body) {
        try { return JSON.readTree(body); } catch (RuntimeException e) { return null; }
    }

    private static String add(Map<String, Piece> pieces, String memberId, String data) {
        String key = key(memberId, data);
        pieces.putIfAbsent(key, new Piece(key, data));
        return key;
    }

    private static String piece(Map<String, String> pieces, String key) {
        String data = pieces.get(key);
        if (data == null) throw new IllegalStateException("Missing body piece");
        return data;
    }

    /** Removes cache markers from a message, recording where each one was. */
    private static void lift(JsonNode node, String field, int index, ArrayNode path, ArrayNode cache) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>(object.propertyNames());
            for (int at = 0; at < names.size(); at++) {
                String name = names.get(at);
                if (MARKER.equals(name)) {
                    ObjectNode marker = cache.addObject();
                    marker.put("f", field).put("i", index).put("at", at);
                    marker.set("p", path.deepCopy());
                    marker.set("v", object.get(name));
                } else {
                    ArrayNode next = path.deepCopy();
                    next.add(name);
                    lift(object.get(name), field, index, next, cache);
                }
            }
            object.remove(MARKER);
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                ArrayNode next = path.deepCopy();
                next.add(i);
                lift(array.get(i), field, index, next, cache);
            }
        }
    }

    private static void insert(ObjectNode object, int at, JsonNode value) {
        List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
        for (var entry : object.properties()) fields.add(Map.entry(entry.getKey(), entry.getValue()));
        object.removeAll();
        for (int i = 0; i <= fields.size(); i++) {
            if (i == at) object.set(MARKER, value);
            if (i < fields.size()) object.set(fields.get(i).getKey(), fields.get(i).getValue());
        }
    }
}
