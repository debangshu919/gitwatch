package app.gitwatch.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LabelFilter {

    private LabelFilter() {
    }

    public static boolean matches(List<String> required, List<String> actual) {
        if (required == null || required.isEmpty()) {
            return true;
        }
        Set<String> have = new HashSet<>();
        if (actual != null) {
            for (String label : actual) {
                if (label != null && !label.isBlank()) {
                    have.add(label);
                }
            }
        }
        for (String label : required) {
            if (label == null || label.isBlank()) {
                continue;
            }
            if (!have.contains(label)) {
                return false;
            }
        }
        return true;
    }
}
