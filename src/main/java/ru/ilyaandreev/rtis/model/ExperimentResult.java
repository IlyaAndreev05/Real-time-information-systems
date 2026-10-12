package ru.ilyaandreev.rtis.model;

import java.util.List;
import java.util.Map;

public record ExperimentResult(String resultId, String moduleId, String algorithmId,
                               long inputRevision, List<Point> points, Map<String, Double> metrics) {
    public ExperimentResult {
        points = List.copyOf(points);
        metrics = Map.copyOf(metrics);
    }
}
