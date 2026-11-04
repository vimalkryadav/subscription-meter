package dev.subscriptionmeter.usage;

import dev.subscriptionmeter.domain.Usage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Observes Responses SSE without interpreting tool instructions or summing cumulative usage twice. */
public final class OpenAIUsageObserver implements UsageObserver {
    private static final int MAX_EVENT=512*1024;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final boolean streaming;
    private final ByteArrayOutputStream line=new ByteArrayOutputStream();
    private final StringBuilder event=new StringBuilder();
    private final Map<String,Long> components=new HashMap<>();
    private JsonNode terminal;
    private boolean completed,failed,malformed,discard,incomplete;
    public OpenAIUsageObserver(boolean streaming){this.streaming=streaming;}
    public void accept(byte[] bytes,int length){
        if(!streaming){if(line.size()+length>MAX_EVENT){malformed=true;return;}line.write(bytes,0,length);return;}
        for(int i=0;i<length;i++){
            byte b=bytes[i];
            if(b=='\n'){
                String text=line.toString(StandardCharsets.UTF_8);line.reset();
                if(text.endsWith("\r"))text=text.substring(0,text.length()-1);
                if(text.isEmpty()){
                    if(!discard&&!event.isEmpty())parse(event.toString());
                    event.setLength(0);discard=false;
                }else if(!discard&&text.startsWith("data:"))event.append(text.substring(5).stripLeading()).append('\n');
            }else if(!discard)line.write(b);
            if(line.size()+event.length()>MAX_EVENT){malformed=true;discard=true;line.reset();event.setLength(0);}
        }
    }
    private void parse(String data){
        if(data.strip().equals("[DONE]"))return; // Never substitutes for response.completed.
        try{
            JsonNode root=JSON.readTree(data);String type=root.path("type").asString();
            if(Set.of("response.completed","response.failed","response.incomplete","error").contains(type)){
                if(type.equals("response.failed")||type.equals("error"))failed=true;
                if(type.equals("response.incomplete"))incomplete=true;
                if(terminal!=null){if(!terminal.equals(root))malformed=true;return;}
                terminal=root;
                JsonNode response=root.path("response");read(response.path("usage"));
                if(type.equals("response.completed")){
                    completed=response.path("status").asString().equals("completed");
                    if(response.has("error")&&!response.path("error").isNull())failed=true;
                }else if(type.equals("response.incomplete"))incomplete=true;
                else failed=true;
            }
        }catch(RuntimeException e){malformed=true;}
    }
    private void read(JsonNode usage){
        components.clear();
        if(!usage.isNull()&&!usage.isMissingNode()&&!usage.isObject())malformed=true;
        field(usage,"input_tokens","input_tokens");field(usage,"output_tokens","output_tokens");field(usage,"total_tokens","total_tokens");
        JsonNode input=usage.path("input_tokens_details"),writes=input.path("cache_write_tokens_details");
        field(input,"cached_tokens","input_tokens_details.cached_tokens");field(input,"cache_write_tokens","input_tokens_details.cache_write_tokens");
        field(writes,"ttl_30m_tokens","input_tokens_details.cache_write_tokens_details.ttl_30m_tokens");
        field(writes,"ttl_12h_tokens","input_tokens_details.cache_write_tokens_details.ttl_12h_tokens");
        field(usage.path("output_tokens_details"),"reasoning_tokens","output_tokens_details.reasoning_tokens");
        if(value("input_tokens_details.cached_tokens")>value("input_tokens")
                || value("input_tokens_details.cache_write_tokens")>value("input_tokens")-value("input_tokens_details.cached_tokens")
                || value("output_tokens_details.reasoning_tokens")>value("output_tokens"))malformed=true;
        if(components.containsKey("total_tokens")&&components.containsKey("input_tokens")&&components.containsKey("output_tokens")){
            try{if(value("total_tokens")!=Math.addExact(value("input_tokens"),value("output_tokens")))malformed=true;}
            catch(ArithmeticException overflow){malformed=true;}
        }
    }
    private void field(JsonNode source,String field,String component){
        JsonNode node=source.path(field);if(node.isMissingNode()||node.isNull())return;
        if(!node.isIntegralNumber()||!node.canConvertToLong()||node.asLong()<0){malformed=true;return;}
        components.put(component,node.asLong());
    }
    private long value(String name){return components.getOrDefault(name,0L);}
    public void finishJson(){
        try{JsonNode root=JSON.readTree(line.toByteArray());read(root.path("usage"));completed=root.path("status").asString().equals("completed");failed=root.has("error")&&!root.path("error").isNull();}
        catch(RuntimeException e){malformed=true;}
    }
    public Usage snapshot(){
        long reads=value("input_tokens_details.cached_tokens"),writes=value("input_tokens_details.cache_write_tokens");
        return new Usage(Math.max(0,value("input_tokens")-reads-writes),value("output_tokens"),writes,reads,!components.isEmpty(),components,complete());
    }
    public boolean complete(){return completed&&!incomplete&&!failed&&!malformed&&components.containsKey("input_tokens")&&components.containsKey("output_tokens");}
    public boolean error(){return failed;}
}
