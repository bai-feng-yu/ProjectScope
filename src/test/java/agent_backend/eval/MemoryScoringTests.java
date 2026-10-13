package agent_backend.eval;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MemoryScoringTests {
    @Test void choiceMustBeUnambiguous() {
        assertEquals(1, MemoryScoring.choice(" (C)\n", "(c)"));
        assertEquals(0, MemoryScoring.choice("(a) or (c)", "(c)"));
        assertEquals(0, MemoryScoring.choice("", "(c)"));
    }
    @Test void forgettingPenaltyUsesPerQuestionRubricAndMissingLabelsAreNotSuccess() {
        var json = JsonMapper.builder().build();
        var rubric = json.readTree("""
                [{"evaluation_question_id":"p","evaluation_type":"memory_presence","expected_answer":"yes"},
                 {"evaluation_question_id":"f","evaluation_type":"forgetting_absence","expected_answer":"no"}]
                """);
        assertEquals(1, MemoryScoring.fama(rubric, json.readTree("{\"p\":\"yes\",\"f\":\"no\"}")));
        assertEquals(0.5, MemoryScoring.fama(rubric, json.readTree("{\"p\":\"yes\",\"f\":\"yes\"}")));
        assertEquals(0, MemoryScoring.fama(rubric, json.readTree("{\"p\":\"no\",\"f\":\"no\"}")));
        assertThrows(IllegalArgumentException.class, () -> MemoryScoring.fama(rubric, json.readTree("{}")));
    }
}
