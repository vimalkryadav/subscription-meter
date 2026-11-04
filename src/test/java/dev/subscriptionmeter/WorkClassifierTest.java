package dev.subscriptionmeter;

import dev.subscriptionmeter.accounting.WorkClassifier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class WorkClassifierTest {
    private final WorkClassifier classifier=new WorkClassifier();
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private String body(Object messages){return JSON.writeValueAsString(Map.of("messages",messages));}
    private Map<String,Object> user(Object content){return Map.of("role","user","content",content);}

    @Test void largeNativeToolSchemasDoNotHideTheLastInstructionOrPolluteItsCategory(){
        String tools=JSON.writeValueAsString(List.of(Map.of("name","fixture","description","deploy test review create ".repeat(12000),"input_schema",Map.of("type","object"))));
        String messages=JSON.writeValueAsString(List.of(user("debug this crash")));
        // The request field order can vary; complete JSON is required in both cases.
        for(String document:List.of("{\"tools\":"+tools+",\"messages\":"+messages+"}","{\"messages\":"+messages+",\"tools\":"+tools+"}")){
            assertThat(document.length()).isGreaterThan(65536);
            var result=classifier.classify(document);
            assertThat(result.category()).isEqualTo("debugging");
            assertThat(result.version()).isEqualTo(WorkClassifier.VERSION);
            assertThat(result.confidence()).isEqualTo("medium");
        }
    }
    @Test void nativeUserTextBlocksIgnoreToolResultsAndInjectedSystemReminders(){
        var instruction=user(List.of(Map.of("type","text","text","<system-reminder>"+"deploy test review create ".repeat(4000)+"</system-reminder>debug this crash")));
        var toolResult=user(List.of(Map.of("type","tool_result","tool_use_id","fixture","content",List.of(Map.of("type","text","text","implement deploy test review")))));
        assertThat(classifier.classify(body(List.of(instruction,Map.of("role","assistant","content","deploy tests"),toolResult))).category()).isEqualTo("debugging");
        // System-reminder-only user messages likewise do not overwrite the task.
        assertThat(classifier.classify(body(List.of(instruction,user("<system-reminder>deploy tests</system-reminder>")))) .category()).isEqualTo("debugging");
    }
    @Test void fieldOrderAndSupportedUserTextShapesAreEquivalent(){
        assertThat(classifier.classify("{\"messages\":[{\"content\":[{\"text\":\"refactor this module\",\"type\":\"text\"}],\"role\":\"user\"}]}").category()).isEqualTo("refactoring");
        assertThat(classifier.classify("{\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"write docs\"}]}]}").category()).isEqualTo("documentation");
        assertThat(classifier.classify("{\"input\":\"review this change\",\"instructions\":\"deploy test create\"}").category()).isEqualTo("review");
    }
    @Test void missingAmbiguousMalformedOversizedOrUnrecognizedEvidenceStaysUnknown(){
        for(String document:List.of("", "{\"messages\":[", "{\"messages\":[]} {}", "{\"messages\":[],\"messages\":[]}",
                "{\"messages\":[],\"input\":\"debug this crash\"}", body(List.of(user("CONTROL_USAGE_OK"))),
                body(List.of(user(List.of(Map.of("type","tool_result","content","debug this crash"))))),
                body(List.of(user("<system-reminder>debug this crash"))),
                " ".repeat(WorkClassifier.MAX_BODY_CHARS+1))){
            var result=classifier.classify(document);
            assertThat(result.category()).isEqualTo("unknown");assertThat(result.confidence()).isEqualTo("low");
        }
        assertThat(classifier.classify(null).category()).isEqualTo("unknown");
    }
    @Test void parserDepthAndInstructionBudgetsAreEnforcedWithoutPartialClassification(){
        String tooDeep="{\"messages\":[{\"role\":\"user\",\"content\":\"debug this crash\"}],\"extra\":"+"[".repeat(80)+"0"+"]".repeat(80)+"}";
        assertThat(classifier.classify(tooDeep).category()).isEqualTo("unknown");
        assertThat(classifier.classify(body(List.of(user("hello ".repeat(1500)+"debug this crash")))).category()).isEqualTo("unknown");
        assertThat(classifier.classify(body(List.of(user("deploy this server"),user("continue")))) .category()).isEqualTo("unknown");
        assertThat(classifier.classify(body(List.of(user("create tests")))).category()).isEqualTo("mixed");
    }
}
