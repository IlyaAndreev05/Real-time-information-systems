package ru.ilyaandreev.rtis;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.fx.ChartViewer;
import ru.ilyaandreev.rtis.core.AlgorithmCatalog;
import ru.ilyaandreev.rtis.core.AnalysisEngine;
import ru.ilyaandreev.rtis.core.SignalMath;
import ru.ilyaandreev.rtis.export.ChartRenderer;
import ru.ilyaandreev.rtis.mcp.McpControlServer;
import ru.ilyaandreev.rtis.model.ExperimentResult;
import ru.ilyaandreev.rtis.model.LabProject;
import ru.ilyaandreev.rtis.model.Point;
import ru.ilyaandreev.rtis.storage.ProjectSession;
import ru.ilyaandreev.rtis.storage.ProjectStore;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class App extends Application {
    private final ProjectStore projectStore = new ProjectStore();
    private ProjectSession session;
    private McpControlServer mcp;
    private ExecutorService worker;
    private Stage stage;
    private ChartViewer chartViewer;
    private ListView<AlgorithmCatalog.Module> modules;
    private ComboBox<AlgorithmCatalog.Algorithm> algorithms;
    private TextField parameters;
    private TableView<PointRow> pointsTable;
    private Label status;
    private ProgressIndicator progress;
    private boolean rendering;

    @Override public void start(Stage primaryStage) throws Exception {
        stage = primaryStage;
        session = new ProjectSession(projectStore, ProjectSession.defaultAutosavePath());
        worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "rtis-gui-calculation"));
        if (session.snapshot().getInput().isEmpty()) {
            session.update(p -> {
                p.setName("Демонстрационный сигнал");
                p.setInput(SignalMath.sine(1, 2, 0, 2, 401));
                return p;
            });
        }
        BorderPane root = buildInterface();
        Scene scene = new Scene(root, 1420, 880);
        scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        primaryStage.setTitle("Системы реального времени — лабораторный практикум");
        primaryStage.setMinWidth(1024);
        primaryStage.setMinHeight(700);
        primaryStage.setScene(scene);
        primaryStage.show();
        session.addListener(p -> Platform.runLater(() -> render(p)));
        if (getParameters().getRaw().contains("--mcp-http")) {
            mcp = new McpControlServer(session, projectStore, this::snapshotWindow);
            mcp.startHttp(8765);
            status.setText("GUI и MCP активны · http://127.0.0.1:8765/mcp");
        }
    }

    private BorderPane buildInterface() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setTop(new VBox(createMenu(), createToolbar()));

        modules = new ListView<>();
        modules.setPrefWidth(220);
        modules.getItems().setAll(AlgorithmCatalog.MODULES);
        modules.setCellFactory(list -> new ListCell<>() {
            private final Label moduleLabel = new Label();

            {
                moduleLabel.setWrapText(true);
                moduleLabel.setMinWidth(0);
                moduleLabel.prefWidthProperty().bind(list.widthProperty().subtract(24));
                moduleLabel.maxWidthProperty().bind(list.widthProperty().subtract(24));
                moduleLabel.setStyle("-fx-text-fill: -fx-text-inner-color;");
                setGraphic(moduleLabel);
                setText(null);
            }

            @Override protected void updateItem(AlgorithmCatalog.Module item, boolean empty) {
                super.updateItem(item, empty);
                moduleLabel.setText(empty || item == null ? "" : item.lab() + "\n" + item.name());
                setPrefHeight(66);
            }
        });
        modules.getSelectionModel().selectedItemProperty().addListener((o, before, module) -> {
            if (!rendering && module != null) selectModule(module);
        });
        VBox left = new VBox(12, new Label("ЛАБОРАТОРИИ"), modules);
        left.setPadding(new Insets(16));
        left.setPrefWidth(250);
        left.getStyleClass().add("side-panel");

        algorithms = new ComboBox<>();
        algorithms.setMaxWidth(Double.MAX_VALUE);
        algorithms.setCellFactory(list -> algorithmCell());
        algorithms.setButtonCell(algorithmCell());
        algorithms.valueProperty().addListener((o, before, value) -> {
            if (!rendering && value != null) selectAlgorithm(value);
        });
        parameters = new TextField("stride=4, aperture=0.1, maxGap=32, bits=8, window=5, alpha=0.25, standardDeviation=0.1");
        parameters.setPromptText("например: stride=4, aperture=0.1");
        Button run = new Button("▶  Рассчитать");
        run.getStyleClass().add("primary-button");
        run.setMaxWidth(Double.MAX_VALUE);
        run.setOnAction(e -> runExperiment());
        Button points = new Button("Изменить точки сигнала");
        points.setMaxWidth(Double.MAX_VALUE);
        points.setOnAction(e -> editPoints());
        Button generate = new Button("Создать гармонический сигнал");
        generate.setMaxWidth(Double.MAX_VALUE);
        generate.setOnAction(e -> generateSignal());
        progress = new ProgressIndicator();
        progress.setMaxSize(30, 30);
        progress.setVisible(false);
        VBox right = new VBox(12, new Label("ЭКСПЕРИМЕНТ"), new Label("Алгоритм"), algorithms,
            new Label("Параметры"), parameters, run, progress, new Separator(), generate, points);
        right.setPadding(new Insets(16));
        right.setPrefWidth(310);
        right.getStyleClass().add("side-panel");

        chartViewer = new ChartViewer(chart(session.snapshot()));
        chartViewer.setPrefSize(800, 560);
        StackPane chartPane = new StackPane(chartViewer);
        chartPane.getStyleClass().add("chart-panel");
        pointsTable = makePointsTable();
        pointsTable.setPrefHeight(185);
        TitledPane tablePane = new TitledPane("Координаты и таблица результата", pointsTable);
        tablePane.setCollapsible(false);
        VBox center = new VBox(10, chartPane, tablePane);
        center.setPadding(new Insets(14));
        VBox.setVgrow(chartPane, Priority.ALWAYS);
        HBox workspace = new HBox(left, center, right);
        HBox.setHgrow(center, Priority.ALWAYS);
        root.setCenter(workspace);
        status = new Label("Автосохранение включено");
        HBox footer = new HBox(status);
        footer.setPadding(new Insets(8, 16, 8, 16));
        footer.getStyleClass().add("status-bar");
        root.setBottom(footer);
        modules.getSelectionModel().selectFirst();
        render(session.snapshot());
        return root;
    }

    private MenuBar createMenu() {
        Menu file = new Menu("Файл");
        MenuItem newProject = new MenuItem("Новый проект");
        newProject.setOnAction(e -> runSafely(() -> session.update(p -> new LabProject())));
        MenuItem open = new MenuItem("Открыть проект…"); open.setOnAction(e -> openProject());
        MenuItem save = new MenuItem("Сохранить как…"); save.setOnAction(e -> saveProject());
        MenuItem exportChart = new MenuItem("Экспорт графика PNG…"); exportChart.setOnAction(e -> saveChart());
        MenuItem exportWindow = new MenuItem("Снимок окна PNG…"); exportWindow.setOnAction(e -> saveWindow());
        file.getItems().addAll(newProject, open, save, new SeparatorMenuItem(), exportChart, exportWindow);
        Menu help = new Menu("Справка");
        MenuItem about = new MenuItem("О проекте"); about.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION,
            "Лабораторный практикум по информационным системам реального времени.\nРезультаты реверса и перенос функций см. в docs/feature-matrix.md.").showAndWait());
        help.getItems().add(about);
        return new MenuBar(file, help);
    }

    private HBox createToolbar() {
        Button run = new Button("Рассчитать"); run.getStyleClass().add("primary-button"); run.setOnAction(e -> runExperiment());
        Button open = new Button("Открыть"); open.setOnAction(e -> openProject());
        Button save = new Button("Сохранить"); save.setOnAction(e -> saveProject());
        HBox bar = new HBox(10, run, open, save);
        bar.setPadding(new Insets(10, 16, 10, 16));
        return bar;
    }

    private ListCell<AlgorithmCatalog.Algorithm> algorithmCell() {
        return new ListCell<>() {
            @Override protected void updateItem(AlgorithmCatalog.Algorithm item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        };
    }

    private TableView<PointRow> makePointsTable() {
        TableView<PointRow> table = new TableView<>();
        TableColumn<PointRow, Number> x = new TableColumn<>("x");
        x.setCellValueFactory(v -> new ReadOnlyDoubleWrapper(v.getValue().x())); x.setPrefWidth(180);
        TableColumn<PointRow, Number> y = new TableColumn<>("y");
        y.setCellValueFactory(v -> new ReadOnlyDoubleWrapper(v.getValue().y())); y.setPrefWidth(180);
        TableColumn<PointRow, String> series = new TableColumn<>("Ряд");
        series.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().series())); series.setPrefWidth(180);
        table.getColumns().addAll(x, y, series);
        table.setPlaceholder(new Label("После расчёта здесь появятся координаты"));
        return table;
    }

    private void selectModule(AlgorithmCatalog.Module module) {
        AlgorithmCatalog.Algorithm selected = AlgorithmCatalog.ALGORITHMS.get(module.algorithms().getFirst());
        algorithms.getItems().setAll(module.algorithms().stream().map(AlgorithmCatalog.ALGORITHMS::get).toList());
        algorithms.getSelectionModel().select(selected);
        runSafely(() -> session.update(p -> { p.setModuleId(module.id()); p.setAlgorithmId(selected.id()); return p; }));
    }

    private void selectAlgorithm(AlgorithmCatalog.Algorithm algorithm) {
        runSafely(() -> session.update(p -> { p.setModuleId(algorithm.moduleId()); p.setAlgorithmId(algorithm.id()); p.setParameters(parseParameters()); return p; }));
    }

    private Map<String, Double> parseParameters() {
        Map<String, Double> values = new java.util.LinkedHashMap<>();
        for (String item : parameters.getText().split(",")) {
            if (item.isBlank()) continue;
            String[] pair = item.trim().split("=", 2);
            if (pair.length != 2) throw new IllegalArgumentException("Параметр должен иметь вид имя=значение: " + item);
            values.put(pair[0].trim(), Double.parseDouble(pair[1].trim()));
        }
        return values;
    }

    private void runExperiment() {
        try {
            Map<String, Double> values = parseParameters();
            session.update(p -> { p.setParameters(values); return p; });
            LabProject input = session.snapshot();
            long revision = session.revision();
            Task<ExperimentResult> task = new Task<>() {
                @Override protected ExperimentResult call() {
                    updateMessage("Выполняется расчёт…");
                    updateProgress(0.1, 1);
                    ExperimentResult result = AnalysisEngine.run(input, revision, this::isCancelled);
                    updateProgress(1, 1);
                    return result;
                }
            };
            task.setOnRunning(e -> { progress.progressProperty().bind(task.progressProperty()); progress.setVisible(true); status.textProperty().bind(task.messageProperty()); });
            task.setOnSucceeded(e -> {
                progress.progressProperty().unbind(); status.textProperty().unbind(); progress.setVisible(false);
                boolean published = session.publishResult(task.getValue());
                status.setText(published ? "Расчёт завершён · " + task.getValue().metrics() : "Расчёт устарел: параметры изменились");
            });
            task.setOnFailed(e -> { progress.progressProperty().unbind(); status.textProperty().unbind(); progress.setVisible(false); showError(task.getException()); });
            worker.submit(task);
        } catch (Exception e) { showError(e); }
    }

    private void generateSignal() {
        Dialog<List<Point>> dialog = new Dialog<>(); dialog.setTitle("Гармонический сигнал"); dialog.setHeaderText("Задайте форму тестового сигнала");
        ButtonType create = new ButtonType("Создать", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(create, ButtonType.CANCEL);
        TextField amplitude = new TextField("1"), frequency = new TextField("2"), phase = new TextField("0"), duration = new TextField("2"), count = new TextField("401");
        GridPane grid = new GridPane(); grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(12));
        String[] labels = {"Амплитуда", "Частота, Гц", "Фаза, рад", "Длительность, с", "Число точек"};
        TextField[] fields = {amplitude, frequency, phase, duration, count};
        for (int i = 0; i < fields.length; i++) { grid.add(new Label(labels[i]), 0, i); grid.add(fields[i], 1, i); }
        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(button -> button == create ? SignalMath.sine(Double.parseDouble(amplitude.getText()), Double.parseDouble(frequency.getText()), Double.parseDouble(phase.getText()), Double.parseDouble(duration.getText()), Integer.parseInt(count.getText())) : null);
        dialog.showAndWait().ifPresent(points -> runSafely(() -> session.update(p -> { p.setInput(points); p.setResult(List.of()); return p; })));
    }

    private void editPoints() {
        LabProject p = session.snapshot();
        TextArea text = new TextArea(ChartRenderer.csv(p.getInput()));
        text.setPrefColumnCount(42); text.setPrefRowCount(20);
        Dialog<List<Point>> dialog = new Dialog<>(); dialog.setTitle("Координаты сигнала");
        dialog.setHeaderText("По одной точке x,y на строку; первая строка может быть заголовком");
        ButtonType apply = new ButtonType("Применить", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL); dialog.getDialogPane().setContent(text);
        dialog.setResultConverter(button -> {
            if (button != apply) return null;
            List<Point> points = new ArrayList<>();
            for (String line : text.getText().lines().toList()) {
                String value = line.trim(); if (value.isEmpty() || value.toLowerCase().startsWith("x,")) continue;
                String[] pair = value.split(",", 2); if (pair.length != 2) throw new IllegalArgumentException("Ожидалась строка x,y: " + value);
                points.add(new Point(Double.parseDouble(pair[0].trim()), Double.parseDouble(pair[1].trim())));
            }
            SignalMath.requireSignal(points); return points;
        });
        try { dialog.showAndWait().ifPresent(points -> runSafely(() -> session.update(old -> { old.setInput(points); old.setResult(List.of()); return old; }))); }
        catch (Exception e) { showError(e); }
    }

    private JFreeChart chart(LabProject project) {
        return ChartRenderer.createChart(project);
    }

    private void render(LabProject project) {
        if (chartViewer == null) return;
        rendering = true;
        chartViewer.setChart(chart(project));
        List<PointRow> rows = new ArrayList<>();
        project.getInput().forEach(point -> rows.add(new PointRow(point.x(), point.y(), "Исходный")));
        project.getResult().forEach(point -> rows.add(new PointRow(point.x(), point.y(), "Результат")));
        pointsTable.setItems(FXCollections.observableArrayList(rows));
        AlgorithmCatalog.Module module = AlgorithmCatalog.MODULES.stream().filter(m -> m.id().equals(project.getModuleId())).findFirst().orElse(AlgorithmCatalog.MODULES.getFirst());
        if (!module.equals(modules.getSelectionModel().getSelectedItem())) modules.getSelectionModel().select(module);
        var algorithm = AlgorithmCatalog.ALGORITHMS.get(project.getAlgorithmId());
        if (algorithm != null) {
            if (!algorithms.getItems().contains(algorithm)) algorithms.getItems().setAll(module.algorithms().stream().map(AlgorithmCatalog.ALGORITHMS::get).toList());
            algorithms.getSelectionModel().select(algorithm);
        }
        rendering = false;
    }

    private void openProject() {
        FileChooser chooser = chooser("Открыть проект", "Проекты JSON", "*.json"); File file = chooser.showOpenDialog(stage);
        if (file != null) runSafely(() -> session.replaceFrom(file.toPath()));
    }

    private void saveProject() {
        FileChooser chooser = chooser("Сохранить проект", "Проекты JSON", "*.json"); File file = chooser.showSaveDialog(stage);
        if (file != null) runSafely(() -> session.saveAs(file.toPath()));
    }

    private void saveChart() {
        FileChooser chooser = chooser("Экспорт графика", "Изображение PNG", "*.png"); File file = chooser.showSaveDialog(stage);
        if (file != null) runSafely(() -> ChartRenderer.savePng(session.snapshot(), file.toPath(), 1600, 1000));
    }

    private void saveWindow() {
        FileChooser chooser = chooser("Снимок окна", "Изображение PNG", "*.png"); File file = chooser.showSaveDialog(stage);
        if (file != null) runSafely(() -> {
            WritableImage image = stage.getScene().snapshot(null);
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
        });
    }

    private byte[] snapshotWindow() {
        if (!Platform.isFxApplicationThread()) {
            java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
            Platform.runLater(() -> result.complete(captureWindowOnUiThread()));
            try { return result.get(10, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception e) { throw new IllegalStateException("Не удалось снять изображение окна", e); }
        }
        return captureWindowOnUiThread();
    }

    private byte[] captureWindowOnUiThread() {
        if (stage == null || !stage.isShowing()) return null;
        try {
            WritableImage image = stage.getScene().snapshot(null);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(SwingFXUtils.fromFXImage(image, new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB)), "png", output);
            return output.toByteArray();
        } catch (Exception e) { return null; }
    }

    private FileChooser chooser(String title, String filter, String pattern) {
        FileChooser chooser = new FileChooser(); chooser.setTitle(title); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(filter, pattern)); return chooser;
    }

    private void runSafely(IoOperation operation) {
        try { operation.run(); }
        catch (Exception e) { showError(e); }
    }
    private void showError(Throwable error) {
        Throwable cause = error == null ? new IllegalStateException("Неизвестная ошибка") : error;
        Alert alert = new Alert(Alert.AlertType.ERROR, cause.getMessage() == null ? cause.toString() : cause.getMessage(), ButtonType.OK);
        alert.setHeaderText("Не удалось выполнить операцию"); alert.showAndWait();
    }
    @FunctionalInterface private interface IoOperation { void run() throws Exception; }
    private record PointRow(double x, double y, String series) { public double getX() { return x; } public double getY() { return y; } public String getSeries() { return series; } }

    @Override public void stop() {
        if (mcp != null) mcp.close();
        if (worker != null) worker.shutdownNow();
    }
}
