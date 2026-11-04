package dev.subscriptionmeter.provider;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Public SIWC HTTP contract. No API-billing fallback or silent request rewriting. */
public final class OpenAIPlanRequest {
    private static final Set<String> UNSUPPORTED=Set.of("background","conversation","max_output_tokens","max_tool_calls","metadata",
            "moderation","multi_agent","prompt","prompt_cache_retention","safety_identifier","temperature","top_logprobs",
            "top_p","truncation","user","previous_response_id");
    private static final Set<String> UNSUPPORTED_TOOLS=Set.of("image_generation","file_search","code_interpreter","computer_use_preview",
            "computer","mcp","tool_search","programmatic_tool_calling");
    private OpenAIPlanRequest(){ }
    public static void validate(JsonNode body){
        if(body==null||!body.isObject()||!body.path("model").isString()||body.path("model").asString().isBlank())throw new IllegalArgumentException("A model is required");
        if(!body.path("store").isBoolean()||body.path("store").asBoolean()||!body.path("stream").isBoolean()||!body.path("stream").asBoolean())
            throw new IllegalArgumentException("ChatGPT plan requests require store:false and stream:true");
        if(!body.path("input").isArray())throw new IllegalArgumentException("ChatGPT plan requests require input history as an array");
        for(String key:UNSUPPORTED)if(body.has(key))throw new IllegalArgumentException("Unsupported ChatGPT plan request field: "+key);
        for(JsonNode item:body.path("input"))if(item.path("role").asString().equals("system"))throw new IllegalArgumentException("Use instructions or developer messages");
        if(body.has("tools")&&!body.path("tools").isArray())throw new IllegalArgumentException("tools must be an array");
        for(JsonNode tool:body.path("tools")){
            String type=tool.path("type").asString();
            if(UNSUPPORTED_TOOLS.contains(type))throw new IllegalArgumentException("Unsupported ChatGPT plan tool: "+type);
            if(type.equals("function")||type.equals("custom"))throw new IllegalArgumentException("Group function/custom tools in namespaces or additional_tools input items");
        }
    }
}
