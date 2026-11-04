package dev.subscriptionmeter.accounting;

import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

/** Bounded, local suggestions. No model calls and no claim to measured productivity. */
public final class WorkClassifier {
    public static final String VERSION="work-rules-v2";
    // Gateway admission permits 16MiB. Classification is background work and deliberately
    // examines at most 4Mi characters of a COMPLETE capture, never a truncated JSON prefix.
    public static final int MAX_BODY_CHARS=4*1024*1024;
    private static final int MAX_INSTRUCTION_CHARS=8192;
    public static final Set<String> CATEGORIES=Set.of("implementation","debugging","testing","refactoring","review",
            "documentation","research","planning","operations","mixed","unknown");
    private static final JsonFactory JSON=JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MAX_BODY_CHARS)
                .maxStringLength(MAX_BODY_CHARS).maxNestingDepth(64).maxTokenCount(200000).maxNameLength(256).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
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
        if(body==null || body.length()>MAX_BODY_CHARS) return unknown();
        try(var parser=JSON.createParser(body)) {
            if(parser.nextToken()!=JsonToken.START_OBJECT)return unknown();
            String instruction="";boolean seenConversation=false;
            while(parser.nextToken()!=JsonToken.END_OBJECT){
                String name=parser.currentName();parser.nextToken();
                if("messages".equals(name)||"input".equals(name)){
                    if(seenConversation)return unknown(); // Ambiguous mixed API shapes are not evidence.
                    seenConversation=true;
                    if(parser.currentToken()==JsonToken.START_ARRAY)instruction=messages(parser);
                    else if("input".equals(name)&&parser.currentToken()==JsonToken.VALUE_STRING)instruction=instruction(parser.getString());
                    else parser.skipChildren();
                } else parser.skipChildren(); // No tool definitions, system text, or metadata classify work.
            }
            if(parser.nextToken()!=null)return unknown(); // Require the whole retained JSON document.
            if(instruction.isBlank()) return unknown();
            String text=instruction.toLowerCase(Locale.ROOT);
            var matches=RULES.entrySet().stream().filter(e->e.getValue().matcher(text).find()).map(Map.Entry::getKey).toList();
            return new Classification(matches.isEmpty()?"unknown":matches.size()==1?matches.getFirst():"mixed",
                    "local_rules",matches.size()==1?"medium":"low",VERSION);
        } catch(RuntimeException e) { return unknown(); }
    }
    private String messages(JsonParser parser){
        String latest="";
        while(parser.nextToken()!=JsonToken.END_ARRAY){
            if(parser.currentToken()!=JsonToken.START_OBJECT){parser.skipChildren();continue;}
            String role="",content="";
            while(parser.nextToken()!=JsonToken.END_OBJECT){
                String name=parser.currentName();parser.nextToken();
                if("role".equals(name)&&parser.currentToken()==JsonToken.VALUE_STRING)role=parser.getString();
                else if("content".equals(name))content=content(parser);
                else parser.skipChildren();
            }
            // Claude follows user instructions with tool-result-only user messages. They
            // must not erase the instruction, nor classify the tools' untrusted output.
            if("user".equals(role)&&!content.isBlank())latest=content;
        }
        return latest;
    }
    private String content(JsonParser parser){
        if(parser.currentToken()==JsonToken.VALUE_STRING)return instruction(parser.getString());
        if(parser.currentToken()!=JsonToken.START_ARRAY){parser.skipChildren();return "";}
        var result=new StringBuilder();
        while(parser.nextToken()!=JsonToken.END_ARRAY){
            if(parser.currentToken()!=JsonToken.START_OBJECT){parser.skipChildren();continue;}
            String type="",text="";
            while(parser.nextToken()!=JsonToken.END_OBJECT){
                String name=parser.currentName();parser.nextToken();
                if("type".equals(name)&&parser.currentToken()==JsonToken.VALUE_STRING)type=parser.getString();
                else if("text".equals(name)&&parser.currentToken()==JsonToken.VALUE_STRING)text=instruction(parser.getString());
                else parser.skipChildren();
            }
            if(("text".equals(type)||"input_text".equals(type))&&!text.isBlank())append(result,text);
        }
        return result.toString();
    }
    private String instruction(String text){
        // Native Claude puts injected system reminders inside user text blocks. Remove
        // those before applying the text budget so a long reminder cannot hide the task.
        var result=new StringBuilder();int from=0;
        while(from<text.length()&&result.length()<MAX_INSTRUCTION_CHARS){
            int marker=text.indexOf("<system-reminder>",from);
            if(marker<0){append(result,text,from,text.length());break;}
            append(result,text,from,marker);
            int end=text.indexOf("</system-reminder>",marker+17);
            if(end<0)break; // Incomplete injected context is never category evidence.
            from=end+18;
        }
        return result.toString();
    }
    private void append(StringBuilder target,String value){append(target,value,0,value.length());}
    private void append(StringBuilder target,String value,int from,int to){
        if(target.length()>=MAX_INSTRUCTION_CHARS||from>=to)return;
        if(!target.isEmpty())target.append(' ');
        target.append(value,from,Math.min(to,from+MAX_INSTRUCTION_CHARS-target.length()));
    }
    private Classification unknown(){return new Classification("unknown","local_rules","low",VERSION);}
}
