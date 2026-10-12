package ru.ilyaandreev.rtis.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Serializable project data. Presentation state and calculated series are persisted with inputs. */
public class LabProject {
    public static final int CURRENT_SCHEMA_VERSION = 3;

    private int schemaVersion = CURRENT_SCHEMA_VERSION;
    private String id = UUID.randomUUID().toString();
    private String name = "Новый проект";
    private String createdAt = Instant.now().toString();
    private String updatedAt = createdAt;
    private String moduleId = "spectrum";
    private String algorithmId = "fourier";
    private List<Point> input = new ArrayList<>();
    private String inputKind = "samples";
    private List<Point> result = new ArrayList<>();
    private Map<String, Double> parameters = new LinkedHashMap<>();
    private Map<String, Double> metrics = new LinkedHashMap<>();
    private long noiseSeed = 4319L;
    private String chartTitle = "Сигнал";
    private String xAxisLabel = "Время, с";
    private String yAxisLabel = "Амплитуда";
    private boolean showGrid = true;
    private int spectrumLowHarmonic = 1;
    private int spectrumHighHarmonic = 12;
    private int selectedHarmonic = 1;
    private boolean lockTwelveHarmonics;
    private boolean notchFilterEnabled;
    private double measurementCursorX;

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
        copy.inputKind = inputKind;
        copy.result = new ArrayList<>(result);
        copy.parameters = new LinkedHashMap<>(parameters);
        copy.metrics = new LinkedHashMap<>(metrics);
        copy.noiseSeed = noiseSeed;
        copy.chartTitle = chartTitle;
        copy.xAxisLabel = xAxisLabel;
        copy.yAxisLabel = yAxisLabel;
        copy.showGrid = showGrid;
        copy.spectrumLowHarmonic = spectrumLowHarmonic;
        copy.spectrumHighHarmonic = spectrumHighHarmonic;
        copy.selectedHarmonic = selectedHarmonic;
        copy.lockTwelveHarmonics = lockTwelveHarmonics;
        copy.notchFilterEnabled = notchFilterEnabled;
        copy.measurementCursorX = measurementCursorX;
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
    public String getInputKind() { return inputKind; }
    public void setInputKind(String value) {
        if (!"samples".equals(value) && !"controlPoints".equals(value))
            throw new IllegalArgumentException("Тип исходных координат должен быть samples или controlPoints");
        inputKind = value;
    }
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
    public int getSpectrumLowHarmonic() { return spectrumLowHarmonic; }
    public void setSpectrumLowHarmonic(int value) { spectrumLowHarmonic = Math.max(0, value); }
    public int getSpectrumHighHarmonic() { return spectrumHighHarmonic; }
    public void setSpectrumHighHarmonic(int value) { spectrumHighHarmonic = Math.max(0, value); }
    public int getSelectedHarmonic() { return selectedHarmonic; }
    public void setSelectedHarmonic(int value) { selectedHarmonic = Math.max(0, value); }
    public boolean isLockTwelveHarmonics() { return lockTwelveHarmonics; }
    public void setLockTwelveHarmonics(boolean value) { lockTwelveHarmonics = value; }
    public boolean isNotchFilterEnabled() { return notchFilterEnabled; }
    public void setNotchFilterEnabled(boolean value) { notchFilterEnabled = value; }
    public double getMeasurementCursorX() { return measurementCursorX; }
    public void setMeasurementCursorX(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Координата курсора должна быть конечным числом");
        measurementCursorX = value;
    }
}
