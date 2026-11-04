package dev.subscriptionmeter.accounting;

import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bounded, local suggestions. No model calls and no claim to measured productivity. */
public final class WorkClassifier {
    public static final Set<String> CATEGORIES=Set.of("implementation","debugging","testing","refactoring","review",
            "documentation","research","planning","operations","mixed","unknown");
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Map<String,Pattern> RULES=new LinkedHashMap<>();
    static {
        RULES.put("debugging",Pattern.compile("\\b(debug|fix|bug|broken|exception|stacktrace|crash|failing)\\b"));
        RULES.put("testing",Pattern.compile("\\b(test|tests|assertions|coverage|regression|junit|pytest)\\b"));
        RULES.put("refactoring",Pattern.compile("\\b(refactor|refactoring|restructure|simplify|deduplicate)\\b"));
        RULES.put("review",Pattern.compile("\\b(review|audit|critique|pull request)\\b"));
        RULES.put("documentation",Pattern.compile("\\b(documentation|readme|docstring|write docs|document this)\\b"));
        RULES.put("research",Pattern.compile("\\b(explain|research|compare|investigate|understand)\\b"));
        RULES.put("planning",Pattern.compile("\\b(plan|design|architecture|roadmap|estimate)\\b"));
        RULES.put("operations",Pattern.compile("\\b(deploy|deployment|docker|kubernetes|terraform|ci/cd|rollback)\\b"));
        RULES.put("implementation",Pattern.compile("\\b(implement|create|build|add|develop)\\b"));
    }
    public record Classification(String category,String method,String confidence,String version) {}
    public Classification classify(String body) {
        if(body==null || body.length()>65536) return unknown();
        try {
            JsonNode root=JSON.readTree(body), messages=root.path("messages");
            if(!messages.isArray()) return unknown();
            String instruction="";
            for(JsonNode message:messages) if(message.path("role").asString("").equals("user")) {
                JsonNode content=message.path("content");
                if(content.isString()) instruction=content.asString();
                else if(content.isArray()) {
                    var text=new StringBuilder();
                    for(JsonNode block:content) if(block.path("type").asString("").equals("text")) text.append(block.path("text").asString("")).append(' ');
                    // A tool-result-only user message doesn't replace the last user instruction.
                    if(!text.isEmpty()) instruction=text.toString();
                }
            }
            if(instruction.isBlank()) return unknown();
            String text=instruction.substring(0,Math.min(instruction.length(),8192)).toLowerCase(Locale.ROOT);
            var matches=RULES.entrySet().stream().filter(e->e.getValue().matcher(text).find()).map(Map.Entry::getKey).toList();
            return new Classification(matches.isEmpty()?"unknown":matches.size()==1?matches.getFirst():"mixed",
                    "local_rules",matches.size()==1?"medium":"low","work-rules-v1");
        } catch(RuntimeException e) { return unknown(); }
    }
    private Classification unknown(){return new Classification("unknown","local_rules","low","work-rules-v1");}
}
