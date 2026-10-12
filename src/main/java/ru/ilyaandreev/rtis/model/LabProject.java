package ru.ilyaandreev.rtis.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Serializable project data. Presentation state and calculated series are persisted with inputs. */
public class LabProject {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private int schemaVersion = CURRENT_SCHEMA_VERSION;
    private String id = UUID.randomUUID().toString();
    private String name = "Новый проект";
    private String createdAt = Instant.now().toString();
    private String updatedAt = createdAt;
    private String moduleId = "spectrum";
    private String algorithmId = "fourier";
    private List<Point> input = new ArrayList<>();
    private List<Point> result = new ArrayList<>();
    private Map<String, Double> parameters = new LinkedHashMap<>();
    private Map<String, Double> metrics = new LinkedHashMap<>();
    private long noiseSeed = 4319L;
    private String chartTitle = "Сигнал";
    private String xAxisLabel = "Время, с";
    private String yAxisLabel = "Амплитуда";
    private boolean showGrid = true;

    public LabProject() { }

    public LabProject copy() {
        LabProject copy = new LabProject();
        copy.schemaVersion = schemaVersion;
        copy.id = id;
        copy.name = name;
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        copy.moduleId = moduleId;
        copy.algorithmId = algorithmId;
        copy.input = new ArrayList<>(input);
        copy.result = new ArrayList<>(result);
        copy.parameters = new LinkedHashMap<>(parameters);
        copy.metrics = new LinkedHashMap<>(metrics);
        copy.noiseSeed = noiseSeed;
        copy.chartTitle = chartTitle;
        copy.xAxisLabel = xAxisLabel;
        copy.yAxisLabel = yAxisLabel;
        copy.showGrid = showGrid;
        return copy;
    }

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int value) { schemaVersion = value; }
    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String value) { createdAt = value; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String value) { updatedAt = value; }
    public String getModuleId() { return moduleId; }
    public void setModuleId(String value) { moduleId = value; }
    public String getAlgorithmId() { return algorithmId; }
    public void setAlgorithmId(String value) { algorithmId = value; }
    public List<Point> getInput() { return input; }
    public void setInput(List<Point> value) { input = value == null ? new ArrayList<>() : new ArrayList<>(value); }
    public List<Point> getResult() { return result; }
    public void setResult(List<Point> value) { result = value == null ? new ArrayList<>() : new ArrayList<>(value); }
    public Map<String, Double> getParameters() { return parameters; }
    public void setParameters(Map<String, Double> value) { parameters = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value); }
    public Map<String, Double> getMetrics() { return metrics; }
    public void setMetrics(Map<String, Double> value) { metrics = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value); }
    public long getNoiseSeed() { return noiseSeed; }
    public void setNoiseSeed(long value) { noiseSeed = value; }
    public String getChartTitle() { return chartTitle; }
    public void setChartTitle(String value) { chartTitle = value; }
    public String getXAxisLabel() { return xAxisLabel; }
    public void setXAxisLabel(String value) { xAxisLabel = value; }
    public String getYAxisLabel() { return yAxisLabel; }
    public void setYAxisLabel(String value) { yAxisLabel = value; }
    public boolean isShowGrid() { return showGrid; }
    public void setShowGrid(boolean value) { showGrid = value; }
}
