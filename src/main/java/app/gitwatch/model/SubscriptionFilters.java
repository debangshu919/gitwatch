package app.gitwatch.model;

import java.util.ArrayList;
import java.util.List;

public class SubscriptionFilters {

    private List<String> labels = new ArrayList<>();

    public List<String> getLabels() {
        return labels;
    }

    public void setLabels(List<String> labels) {
        this.labels = labels == null ? new ArrayList<>() : labels;
    }
}
