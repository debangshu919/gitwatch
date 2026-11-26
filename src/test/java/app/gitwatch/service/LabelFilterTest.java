package app.gitwatch.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LabelFilterTest {

    @Test
    void singleRequiredLabelSkipsMissingAndMatchesPresent() {
        List<String> required = List.of("good first issue");
        assertFalse(LabelFilter.matches(required, List.of("bug")));
        assertFalse(LabelFilter.matches(required, List.of()));
        assertTrue(LabelFilter.matches(required, List.of("good first issue")));
        assertTrue(LabelFilter.matches(required, List.of("bug", "good first issue")));
    }

    @Test
    void severalRequiredLabelsAreAnd() {
        List<String> required = List.of("bug", "good first issue");
        assertFalse(LabelFilter.matches(required, List.of("bug")));
        assertFalse(LabelFilter.matches(required, List.of("good first issue")));
        assertTrue(LabelFilter.matches(required, List.of("good first issue", "bug", "docs")));
    }

    @Test
    void emptyFilterMatchesEveryLabelSet() {
        assertTrue(LabelFilter.matches(List.of(), List.of()));
        assertTrue(LabelFilter.matches(List.of(), List.of("bug")));
        assertTrue(LabelFilter.matches(null, List.of("bug")));
    }
}
