package ru.ilyaandreev.rtis;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.fx.ChartViewer;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.ui.RectangleEdge;
import ru.ilyaandreev.rtis.core.AlgorithmCatalog;
import ru.ilyaandreev.rtis.core.AnalysisEngine;
import ru.ilyaandreev.rtis.core.ParameterSpec;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private VBox parameterRows;
    private final Map<String, TextField> parameterFields = new LinkedHashMap<>();
    private String parameterAlgorithmId;
    private TableView<PointRow> pointsTable;
    private Label inputKindLabel;
    private TableView<Point> zeroCrossingsTable;
    private TableView<HarmonicRow> harmonicsTable;
    private TableView<CursorRow> cursorTable;
    private Slider cursorSlider;
    private Slider lowHarmonicSlider;
    private Slider highHarmonicSlider;
    private Slider selectedHarmonicSlider;
    private Label cursorReadout;
    private Label lowHarmonicValue;
    private Label highHarmonicValue;
    private Label selectedHarmonicValue;
    private CheckBox lockTwelveHarmonics;
    private CheckBox notchFilter;
    private TitledPane spectrumPane;
    private ToggleButton addPointsMode;
    private ToggleButton cursorMode;
    private Label status;
    private ProgressIndicator progress;
    private boolean rendering;
    private double manualDomainMin, manualDomainMax, manualRangeMin, manualRangeMax;
    private boolean manualAxisCaptured;

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
        modules.setPrefWidth(250);
        modules.getItems().setAll(AlgorithmCatalog.MODULES);
        modules.setCellFactory(list -> new ListCell<>() {
            private final Label moduleLabel = new Label();
            private final Label labBadge = new Label();
            private final VBox content = new VBox(3, moduleLabel, labBadge);

            {
                moduleLabel.setWrapText(true);
                moduleLabel.setMinWidth(0);
                moduleLabel.prefWidthProperty().bind(list.widthProperty().subtract(24));
                moduleLabel.maxWidthProperty().bind(list.widthProperty().subtract(24));
                moduleLabel.getStyleClass().add("module-title");
                labBadge.getStyleClass().add("lab-badge");
                setGraphic(content);
                setText(null);
            }

            @Override protected void updateItem(AlgorithmCatalog.Module item, boolean empty) {
                super.updateItem(item, empty);
                moduleLabel.setText(empty || item == null ? "" : item.name());
                labBadge.setText(empty || item == null || item.labTag() == null ? "" : "ЛР " + item.labTag());
                labBadge.setVisible(!empty && item != null && item.labTag() != null);
                setPrefHeight(62);
            }
        });
        modules.getSelectionModel().selectedItemProperty().addListener((o, before, module) -> {
            if (!rendering && module != null) selectModule(module);
        });
        VBox left = new VBox(12, new Label("МОДУЛИ"), modules);
        left.setPadding(new Insets(16));
        left.setPrefWidth(280);
        left.getStyleClass().add("side-panel");

        algorithms = new ComboBox<>();
        algorithms.setMaxWidth(Double.MAX_VALUE);
        algorithms.setCellFactory(list -> algorithmCell());
        algorithms.setButtonCell(algorithmCell());
        algorithms.valueProperty().addListener((o, before, value) -> {
            if (!rendering && value != null) selectAlgorithm(value);
        });
        parameterRows = new VBox(8);
        Button run = new Button("▶  Рассчитать");
        run.getStyleClass().add("primary-button");
        run.setMaxWidth(Double.MAX_VALUE);
        run.setOnAction(e -> runExperiment());
        Button generate = new Button("Создать гармонический сигнал");
        generate.setMaxWidth(Double.MAX_VALUE);
        generate.setOnAction(e -> generateSignal());
        progress = new ProgressIndicator();
        progress.setMaxSize(30, 30);
        progress.setVisible(false);
        buildSpectrumControls();
        VBox controls = new VBox(12, new Label("ОПЕРАЦИЯ"), new Label("Алгоритм"), algorithms,
            new Label("Параметры"), parameterRows, run, progress, spectrumPane, new Separator(),
            new Label("Исходный сигнал"), generate);
        controls.setPadding(new Insets(16));
        ScrollPane rightScroll = new ScrollPane(controls);
        rightScroll.setFitToWidth(true);
        rightScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox right = new VBox(rightScroll);
        right.setPrefWidth(320);
        right.getStyleClass().add("side-panel");
        VBox.setVgrow(rightScroll, Priority.ALWAYS);

        chartViewer = new ChartViewer(chart(session.snapshot()));
        chartViewer.setPrefSize(800, 560);
        chartViewer.getCanvas().setOnMouseClicked(this::handleChartClick);
        StackPane chartPane = new StackPane(chartViewer);
        chartPane.getStyleClass().add("chart-panel");
        pointsTable = makePointsTable();
        inputKindLabel = new Label();
        zeroCrossingsTable = makePointListTable("Время, с", "Амплитуда");
        harmonicsTable = makeHarmonicsTable();
        cursorTable = makeCursorTable();
        TabPane dataTabs = new TabPane();
        HBox pointTools = new HBox(8);
        addPointsMode = new ToggleButton("Добавить точки щелчками");
        addPointsMode.setOnAction(e -> {
            if (addPointsMode.isSelected()) {
                cursorMode.setSelected(false);
                captureManualAxisRange();
                status.setText("Щёлкайте по графику, чтобы добавить точки");
            } else {
                manualAxisCaptured = false;
                status.setText("Режим ввода точек выключен");
            }
            if (chartViewer != null) {
                chartViewer.setChart(chart(session.snapshot()));
                applyManualAxisRange();
            }
        });
        cursorMode = new ToggleButton("Перемещать курсор на графике");
        cursorMode.setOnAction(e -> {
            if (cursorMode.isSelected()) { addPointsMode.setSelected(false); status.setText("Щёлкните по графику, чтобы установить измерительный курсор"); }
            else status.setText("Режим курсора выключен");
            if (chartViewer != null) chartViewer.setChart(chart(session.snapshot()));
        });
        Button deletePoint = new Button("Удалить выбранную точку");
        deletePoint.setOnAction(e -> deleteSelectedPoint());
        Button editPointList = new Button("Ввод списком…");
        editPointList.setOnAction(e -> editPoints());
        pointTools.getChildren().addAll(addPointsMode, cursorMode, deletePoint, editPointList);
        VBox pointsContent = new VBox(8, pointTools, inputKindLabel, pointsTable);
        Tab pointsTab = new Tab("Координаты", pointsContent); pointsTab.setClosable(false);

        VBox crossingContent = new VBox(8, createCursorControls(), cursorReadout, zeroCrossingsTable);
        Tab crossingTab = new Tab("Курсор и нулевые пересечения", crossingContent); crossingTab.setClosable(false);
        Tab harmonicsTab = new Tab("Гармоники", harmonicsTable); harmonicsTab.setClosable(false);
        dataTabs.getTabs().addAll(pointsTab, crossingTab, harmonicsTab);
        dataTabs.setPrefHeight(270);
        VBox center = new VBox(10, chartPane, dataTabs);
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
        table.setEditable(true);
        TableColumn<PointRow, Number> index = new TableColumn<>("№");
        index.setCellValueFactory(v -> new ReadOnlyIntegerWrapper(v.getValue().index() + 1)); index.setPrefWidth(55);
        TableColumn<PointRow, String> x = new TableColumn<>("x");
        x.setCellValueFactory(v -> new ReadOnlyStringWrapper(format(v.getValue().x()))); x.setPrefWidth(180);
        x.setCellFactory(TextFieldTableCell.forTableColumn());
        x.setOnEditCommit(e -> editTablePointText(e.getRowValue(), e.getNewValue(), format(e.getRowValue().y())));
        TableColumn<PointRow, String> y = new TableColumn<>("y");
        y.setCellValueFactory(v -> new ReadOnlyStringWrapper(format(v.getValue().y()))); y.setPrefWidth(180);
        y.setCellFactory(TextFieldTableCell.forTableColumn());
        y.setOnEditCommit(e -> editTablePointText(e.getRowValue(), format(e.getRowValue().x()), e.getNewValue()));
        TableColumn<PointRow, String> series = new TableColumn<>("Ряд");
        series.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().series())); series.setPrefWidth(180);
        table.getColumns().addAll(index, x, y, series);
        table.setPlaceholder(new Label("Добавьте точки щелчками по графику или вводом списком"));
        table.setPrefHeight(205);
        return table;
    }

    private TableView<Point> makePointListTable(String xTitle, String yTitle) {
        TableView<Point> table = new TableView<>();
        TableColumn<Point, Number> x = new TableColumn<>(xTitle);
        x.setCellValueFactory(v -> new ReadOnlyDoubleWrapper(v.getValue().x())); x.setPrefWidth(220);
        TableColumn<Point, Number> y = new TableColumn<>(yTitle);
        y.setCellValueFactory(v -> new ReadOnlyDoubleWrapper(v.getValue().y())); y.setPrefWidth(220);
        table.getColumns().addAll(x, y);
        table.setPrefHeight(210);
        return table;
    }

    private TableView<HarmonicRow> makeHarmonicsTable() {
        TableView<HarmonicRow> table = new TableView<>();
        TableColumn<HarmonicRow, Number> n = new TableColumn<>("Гармоника");
        n.setCellValueFactory(v -> new ReadOnlyDoubleWrapper(v.getValue().number())); n.setPrefWidth(120);
        TableColumn<HarmonicRow, Number> f = new TableColumn<>("Частота, Гц");
        f.setCellValueFactory(v -> new ReadOnlyDoubleWrapper(v.getValue().frequency())); f.setPrefWidth(180);
        TableColumn<HarmonicRow, Number> a = new TableColumn<>("Амплитуда");
        a.setCellValueFactory(v -> new ReadOnlyDoubleWrapper(v.getValue().amplitude())); a.setPrefWidth(180);
        table.getColumns().addAll(n, f, a);
        table.setPlaceholder(new Label("Сначала рассчитайте амплитудный спектр"));
        table.setPrefHeight(235);
        return table;
    }

    private TableView<CursorRow> makeCursorTable() {
        TableView<CursorRow> table = new TableView<>();
        table.setEditable(true);
        TableColumn<CursorRow, String> x = new TableColumn<>("Координата курсора, с");
        x.setCellValueFactory(v -> new ReadOnlyStringWrapper(format(v.getValue().x())));
        x.setCellFactory(TextFieldTableCell.forTableColumn());
        x.setOnEditCommit(e -> {
            try { setCursor(parseNumber(e.getNewValue())); }
            catch (Exception error) { showError(error); }
        });
        TableColumn<CursorRow, Number> y = new TableColumn<>("Амплитуда сигнала");
        y.setCellValueFactory(v -> new ReadOnlyDoubleWrapper(v.getValue().y()));
        table.getColumns().addAll(x, y);
        table.setPrefHeight(90);
        return table;
    }

    private void buildSpectrumControls() {
        lowHarmonicSlider = new Slider(0, 100, 1);
        highHarmonicSlider = new Slider(0, 100, 12);
        selectedHarmonicSlider = new Slider(0, 100, 1);
        for (Slider slider : List.of(lowHarmonicSlider, highHarmonicSlider, selectedHarmonicSlider)) {
            slider.setShowTickMarks(false);
            slider.setBlockIncrement(1);
            slider.setMajorTickUnit(1);
            slider.setSnapToTicks(true);
        }
        lowHarmonicValue = new Label("1");
        highHarmonicValue = new Label("12");
        selectedHarmonicValue = new Label("1");
        lowHarmonicSlider.valueProperty().addListener((o, before, value) -> {
            if (lockTwelveHarmonics != null && lockTwelveHarmonics.isSelected())
                highHarmonicSlider.setValue(Math.min(highHarmonicSlider.getMax(), value.doubleValue() + 11));
            updateHarmonicLabels();
        });
        highHarmonicSlider.valueProperty().addListener((o, before, value) -> {
            if (!lockTwelveHarmonics.isSelected() && value.doubleValue() < lowHarmonicSlider.getValue())
                lowHarmonicSlider.setValue(value.doubleValue());
            updateHarmonicLabels();
        });
        selectedHarmonicSlider.valueProperty().addListener((o, before, value) -> updateHarmonicLabels());
        List.of(lowHarmonicSlider, highHarmonicSlider).forEach(slider -> {
            slider.setOnMouseReleased(e -> persistSpectrumRange());
            slider.setOnKeyReleased(e -> persistSpectrumRange());
        });
        selectedHarmonicSlider.setOnMouseReleased(e -> persistSelectedHarmonic());
        selectedHarmonicSlider.setOnKeyReleased(e -> persistSelectedHarmonic());

        lockTwelveHarmonics = new CheckBox("Фиксировать интервал в 12 гармоник");
        lockTwelveHarmonics.setOnAction(e -> persistSpectrumRange());
        notchFilter = new CheckBox("Фильтр «Пробка»");
        notchFilter.setTooltip(new Tooltip("Состояние сохраняется в проекте; численное действие фильтра ещё сверяется с оригиналом."));
        notchFilter.setOnAction(e -> runSafely(() -> session.updatePresentation(p -> {
            p.setNotchFilterEnabled(notchFilter.isSelected()); return p;
        })));
        VBox content = new VBox(7,
            sliderRow("Нижняя граница", lowHarmonicSlider, lowHarmonicValue),
            sliderRow("Верхняя граница", highHarmonicSlider, highHarmonicValue),
            lockTwelveHarmonics,
            new Separator(),
            sliderRow("Выбранная гармоника", selectedHarmonicSlider, selectedHarmonicValue),
            notchFilter);
        content.setPadding(new Insets(8, 2, 4, 2));
        spectrumPane = new TitledPane("Спектральный диапазон", content);
        spectrumPane.setExpanded(false);
    }

    private HBox sliderRow(String title, Slider slider, Label value) {
        VBox labels = new VBox(3, new Label(title), slider);
        HBox.setHgrow(labels, Priority.ALWAYS);
        value.setMinWidth(32);
        value.setStyle("-fx-font-weight: bold; -fx-alignment: center-right;");
        return new HBox(8, labels, value);
    }

    private VBox createCursorControls() {
        cursorSlider = new Slider();
        cursorSlider.setShowTickMarks(true);
        cursorSlider.setShowTickLabels(false);
        cursorSlider.valueProperty().addListener((o, before, value) -> updateCursorReadout(value.doubleValue()));
        cursorSlider.setOnMouseReleased(e -> setCursor(cursorSlider.getValue()));
        cursorSlider.setOnKeyReleased(e -> setCursor(cursorSlider.getValue()));
        cursorReadout = new Label("Координаты появятся после выбора сигнала");
        cursorReadout.getStyleClass().add("measurement-readout");
        Label hint = new Label("Положение можно менять ползунком, щелчком по графику или редактированием x в таблице.");
        hint.setWrapText(true);
        return new VBox(8, hint, cursorSlider);
    }

    private void updateHarmonicLabels() {
        if (lowHarmonicValue == null) return;
        lowHarmonicValue.setText(Integer.toString((int) Math.round(lowHarmonicSlider.getValue())));
        highHarmonicValue.setText(Integer.toString((int) Math.round(highHarmonicSlider.getValue())));
        selectedHarmonicValue.setText(Integer.toString((int) Math.round(selectedHarmonicSlider.getValue())));
    }

    private void updateSpectrumControls(LabProject project) {
        int max = project.getAlgorithmId().equals("fourier") && !project.getResult().isEmpty()
            ? Math.max(1, project.getResult().size() - 1) : 100;
        rendering = true;
        for (Slider slider : List.of(lowHarmonicSlider, highHarmonicSlider, selectedHarmonicSlider)) slider.setMax(max);
        lowHarmonicSlider.setValue(Math.min(max, project.getSpectrumLowHarmonic()));
        highHarmonicSlider.setValue(Math.min(max, Math.max(project.getSpectrumLowHarmonic(), project.getSpectrumHighHarmonic())));
        selectedHarmonicSlider.setValue(Math.min(max, project.getSelectedHarmonic()));
        lockTwelveHarmonics.setSelected(project.isLockTwelveHarmonics());
        notchFilter.setSelected(project.isNotchFilterEnabled());
        rendering = false;
        updateHarmonicLabels();
        List<HarmonicRow> rows = new ArrayList<>();
        if (project.getAlgorithmId().equals("fourier") && !project.getResult().isEmpty()) {
            int low = Math.min(project.getResult().size() - 1, project.getSpectrumLowHarmonic());
            int high = Math.min(project.getResult().size() - 1, Math.max(low, project.getSpectrumHighHarmonic()));
            for (int k = low; k <= high; k++) {
                Point point = project.getResult().get(k);
                rows.add(new HarmonicRow(k, point.x(), point.y()));
            }
        }
        harmonicsTable.setItems(FXCollections.observableArrayList(rows));
    }

    private void updateCursorControls(LabProject project) {
        if (project.getInput().size() < 2) {
            cursorSlider.setDisable(true);
            cursorTable.setItems(FXCollections.observableArrayList());
            cursorReadout.setText("Добавьте не менее двух точек сигнала");
            zeroCrossingsTable.setItems(FXCollections.observableArrayList());
            return;
        }
        double min = project.getInput().getFirst().x(), max = project.getInput().getLast().x();
        rendering = true;
        cursorSlider.setDisable(false);
        cursorSlider.setMin(min);
        cursorSlider.setMax(max);
        cursorSlider.setBlockIncrement(Math.max((max - min) / 100, 1e-9));
        cursorSlider.setMajorTickUnit(Math.max((max - min) / 10, 1e-9));
        cursorSlider.setValue(Math.max(min, Math.min(max, project.getMeasurementCursorX())));
        rendering = false;
        updateCursorReadout(cursorSlider.getValue());
        try {
            Point measurement = SignalMath.interpolateAt(project.getInput(), cursorSlider.getValue());
            cursorTable.setItems(FXCollections.observableArrayList(new CursorRow(measurement.x(), measurement.y())));
            zeroCrossingsTable.setItems(FXCollections.observableArrayList(SignalMath.zeroCrossings(project.getInput())));
        } catch (Exception e) {
            cursorTable.setItems(FXCollections.observableArrayList());
        }
    }

    private void updateCursorReadout(double x) {
        if (cursorReadout == null || session == null) return;
        List<Point> input = session.snapshot().getInput();
        if (input.size() < 2) return;
        try {
            Point value = SignalMath.interpolateAt(input, x);
            cursorReadout.setText("x = " + format(value.x()) + " с    y = " + format(value.y()));
            if (cursorTable != null) cursorTable.setItems(FXCollections.observableArrayList(new CursorRow(value.x(), value.y())));
        } catch (Exception e) { cursorReadout.setText("Курсор находится вне сигнала"); }
    }

    private void persistSpectrumRange() {
        if (rendering) return;
        int max = (int) Math.round(lowHarmonicSlider.getMax());
        int low = (int) Math.round(lowHarmonicSlider.getValue());
        int high = (int) Math.round(highHarmonicSlider.getValue());
        if (lockTwelveHarmonics.isSelected()) high = Math.min(max, low + 11);
        if (high < low) high = low;
        final int normalizedHigh = high;
        runSafely(() -> session.updatePresentation(p -> {
            p.setSpectrumLowHarmonic(low); p.setSpectrumHighHarmonic(normalizedHigh);
            p.setLockTwelveHarmonics(lockTwelveHarmonics.isSelected()); return p;
        }));
    }

    private void persistSelectedHarmonic() {
        if (rendering) return;
        int value = (int) Math.round(selectedHarmonicSlider.getValue());
        runSafely(() -> session.updatePresentation(p -> { p.setSelectedHarmonic(value); return p; }));
    }

    private void setCursor(double x) {
        if (rendering) return;
        runSafely(() -> session.updatePresentation(p -> {
            if (p.getInput().size() < 2) throw new IllegalArgumentException("Сначала добавьте точки сигнала");
            SignalMath.interpolateAt(p.getInput(), x);
            p.setMeasurementCursorX(x); return p;
        }));
    }

    private void handleChartClick(MouseEvent event) {
        if (rendering || (!addPointsMode.isSelected() && !cursorMode.isSelected())) return;
        var info = chartViewer.getRenderingInfo();
        var dataArea = info == null ? null : info.getPlotInfo().getDataArea();
        if (dataArea == null || !dataArea.contains(event.getX(), event.getY())) return;
        XYPlot plot = chartViewer.getChart().getXYPlot();
        double x = plot.getDomainAxis().java2DToValue(event.getX(), dataArea, plot.getDomainAxisEdge());
        double y = plot.getRangeAxis().java2DToValue(event.getY(), dataArea, plot.getRangeAxisEdge());
        if (cursorMode.isSelected()) setCursor(x);
        else addPoint(x, y);
        event.consume();
    }

    private void captureManualAxisRange() {
        if (chartViewer == null) return;
        XYPlot plot = chartViewer.getChart().getXYPlot();
        manualDomainMin = plot.getDomainAxis().getLowerBound();
        manualDomainMax = plot.getDomainAxis().getUpperBound();
        manualRangeMin = plot.getRangeAxis().getLowerBound();
        manualRangeMax = plot.getRangeAxis().getUpperBound();
        manualAxisCaptured = Double.isFinite(manualDomainMin) && Double.isFinite(manualDomainMax)
            && Double.isFinite(manualRangeMin) && Double.isFinite(manualRangeMax)
            && manualDomainMax > manualDomainMin && manualRangeMax > manualRangeMin;
    }

    private void applyManualAxisRange() {
        if (!manualAxisCaptured || addPointsMode == null || !addPointsMode.isSelected() || chartViewer == null) return;
        XYPlot plot = chartViewer.getChart().getXYPlot();
        plot.getDomainAxis().setRange(manualDomainMin, manualDomainMax);
        plot.getRangeAxis().setRange(manualRangeMin, manualRangeMax);
    }

    private void addPoint(double x, double y) {
        try {
            session.update(p -> {
                List<Point> points = new ArrayList<>(p.getInput());
                if (points.stream().anyMatch(point -> Math.abs(point.x() - x) < Math.ulp(Math.max(1, Math.abs(x))) * 8))
                    throw new IllegalArgumentException("В этой позиции уже есть точка; приблизьте график или измените её в таблице");
                points.add(new Point(x, y)); points.sort(Comparator.comparingDouble(Point::x));
                if (points.size() >= 2) SignalMath.requireSignal(points);
                p.setInput(points); p.setInputKind("controlPoints"); return p;
            });
            status.setText("Точка добавлена: x=" + format(x) + ", y=" + format(y));
        } catch (Exception e) { showError(e); }
    }

    private void editTablePoint(PointRow row, double x, double y) {
        if (!row.series().equals("Исходный")) return;
        runSafely(() -> session.update(p -> {
            List<Point> points = new ArrayList<>(p.getInput());
            points.set(row.index(), new Point(x, y)); points.sort(Comparator.comparingDouble(Point::x));
            SignalMath.requireSignal(points); p.setInput(points); return p;
        }));
    }

    private void editTablePointText(PointRow row, String x, String y) {
        try { editTablePoint(row, parseNumber(x), parseNumber(y)); }
        catch (Exception error) { showError(error); }
    }

    private void deleteSelectedPoint() {
        PointRow row = pointsTable.getSelectionModel().getSelectedItem();
        if (row == null || !row.series().equals("Исходный")) { status.setText("Выберите строку исходного сигнала"); return; }
        runSafely(() -> session.update(p -> {
            List<Point> points = new ArrayList<>(p.getInput()); points.remove(row.index()); p.setInput(points); return p;
        }));
    }

    private void selectModule(AlgorithmCatalog.Module module) {
        AlgorithmCatalog.Algorithm selected = AlgorithmCatalog.ALGORITHMS.get(module.algorithms().getFirst());
        rendering = true;
        algorithms.getItems().setAll(module.algorithms().stream().map(AlgorithmCatalog.ALGORITHMS::get).toList());
        algorithms.getSelectionModel().select(selected);
        rendering = false;
        runSafely(() -> session.update(p -> {
            p.setModuleId(module.id()); p.setAlgorithmId(selected.id());
            p.setParameters(parametersFor(selected, p.getParameters())); return p;
        }));
    }

    private void selectAlgorithm(AlgorithmCatalog.Algorithm algorithm) {
        runSafely(() -> session.update(p -> {
            p.setModuleId(algorithm.moduleId()); p.setAlgorithmId(algorithm.id());
            p.setParameters(parametersFor(algorithm, p.getParameters())); return p;
        }));
    }

    private Map<String, Double> parametersFor(AlgorithmCatalog.Algorithm algorithm, Map<String, Double> oldValues) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (ParameterSpec spec : algorithm.parameters())
            values.put(spec.id(), spec.clamp(oldValues.getOrDefault(spec.id(), spec.defaultValue())));
        return values;
    }

    private void renderParameterControls(AlgorithmCatalog.Algorithm algorithm, LabProject project) {
        if (algorithm == null) return;
        if (!algorithm.id().equals(parameterAlgorithmId)) {
            parameterRows.getChildren().clear();
            parameterFields.clear();
            parameterAlgorithmId = algorithm.id();
            for (ParameterSpec spec : algorithm.parameters()) {
                Label label = new Label(spec.name() + (spec.unit().isBlank() ? "" : ", " + spec.unit()));
                TextField field = new TextField(format(project.getParameters().getOrDefault(spec.id(), spec.defaultValue())));
                field.setAccessibleText(spec.name() + ", диапазон " + format(spec.min()) + "–" + format(spec.max()));
                field.setPrefColumnCount(8);
                Button minus = new Button("−"); minus.getStyleClass().add("stepper-button");
                Button plus = new Button("+"); plus.getStyleClass().add("stepper-button");
                minus.setAccessibleText("Уменьшить: " + spec.name()); plus.setAccessibleText("Увеличить: " + spec.name());
                minus.setOnAction(e -> stepParameter(spec, field, -1));
                plus.setOnAction(e -> stepParameter(spec, field, 1));
                field.setOnAction(e -> persistParameterFields());
                field.focusedProperty().addListener((o, before, focused) -> { if (!focused && before) persistParameterFields(); });
                HBox value = new HBox(5, minus, field, plus);
                HBox.setHgrow(field, Priority.ALWAYS);
                parameterRows.getChildren().add(new VBox(4, label, value));
                parameterFields.put(spec.id(), field);
            }
            if (algorithm.parameters().isEmpty()) parameterRows.getChildren().add(new Label("Для этой операции параметры не требуются."));
        } else {
            algorithm.parameters().forEach(spec -> {
                TextField field = parameterFields.get(spec.id());
                if (field != null && !field.isFocused()) field.setText(format(project.getParameters().getOrDefault(spec.id(), spec.defaultValue())));
            });
        }
    }

    private void stepParameter(ParameterSpec spec, TextField field, int direction) {
        try {
            double current = parseNumber(field.getText());
            field.setText(format(spec.clamp(current + direction * spec.step())));
            persistParameterFields();
        } catch (Exception e) { showError(e); }
    }

    private Map<String, Double> parseParameters() {
        AlgorithmCatalog.Algorithm algorithm = AlgorithmCatalog.ALGORITHMS.get(session.snapshot().getAlgorithmId());
        Map<String, Double> values = new LinkedHashMap<>();
        if (algorithm == null) return values;
        for (ParameterSpec spec : algorithm.parameters()) {
            TextField field = parameterFields.get(spec.id());
            double value = field == null ? spec.defaultValue() : parseNumber(field.getText());
            if (value < spec.min() || value > spec.max())
                throw new IllegalArgumentException(spec.name() + " должен быть от " + format(spec.min()) + " до " + format(spec.max()));
            if (spec.integer() && value != Math.rint(value)) throw new IllegalArgumentException(spec.name() + " должен быть целым числом");
            values.put(spec.id(), spec.clamp(value));
        }
        return values;
    }

    private void persistParameterFields() {
        if (rendering || parameterFields.isEmpty()) return;
        try {
            Map<String, Double> values = parseParameters();
            session.update(p -> { p.setParameters(values); return p; });
        } catch (Exception e) { status.setText(e.getMessage()); }
    }

    private static double parseNumber(String input) {
        String normalized = input == null ? "" : input.trim().replace(" ", "").replace(',', '.');
        double value = Double.parseDouble(normalized);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Введите конечное число");
        return value;
    }

    private static String format(double value) {
        String text = String.format(Locale.ROOT, "%.6f", value);
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
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
        dialog.showAndWait().ifPresent(points -> runSafely(() -> session.update(p -> {
            p.setInput(points); p.setInputKind("samples"); p.setResult(List.of()); return p;
        })));
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
                String delimiter = value.contains(";") ? ";" : value.contains("\t") ? "\\t" : ",";
                String[] pair = value.split(delimiter, 2); if (pair.length != 2) throw new IllegalArgumentException("Ожидалась строка x,y (для десятичных запятых используйте точку с запятой): " + value);
                points.add(new Point(parseNumber(pair[0]), parseNumber(pair[1])));
            }
            SignalMath.requireSignal(points); return points;
        });
        try { dialog.showAndWait().ifPresent(points -> runSafely(() -> session.update(old -> {
            old.setInput(points); old.setInputKind("controlPoints"); old.setResult(List.of()); return old;
        }))); }
        catch (Exception e) { showError(e); }
    }

    private JFreeChart chart(LabProject project) {
        LabProject display = project;
        if ((addPointsMode != null && addPointsMode.isSelected() || cursorMode != null && cursorMode.isSelected())
            && project.getAlgorithmId().equals("fourier") && !project.getResult().isEmpty()) {
            display = project.copy();
            display.setAlgorithmId("manual-signal-view");
            display.setResult(List.of());
        }
        return ChartRenderer.createChart(display);
    }

    private void render(LabProject project) {
        if (chartViewer == null) return;
        rendering = true;
        chartViewer.setChart(chart(project));
        applyManualAxisRange();
        List<PointRow> rows = new ArrayList<>();
        for (int i = 0; i < project.getInput().size(); i++) {
            Point point = project.getInput().get(i); rows.add(new PointRow(i, point.x(), point.y(), "Исходный"));
        }
        for (int i = 0; i < project.getResult().size(); i++) {
            Point point = project.getResult().get(i); rows.add(new PointRow(i, point.x(), point.y(), "Результат"));
        }
        pointsTable.setItems(FXCollections.observableArrayList(rows));
        inputKindLabel.setText("Режим ряда: " + ("controlPoints".equals(project.getInputKind())
            ? "опорные точки · интерполяция проходит через каждую точку"
            : "дискретные отсчёты"));
        AlgorithmCatalog.Module module = AlgorithmCatalog.MODULES.stream().filter(m -> m.id().equals(project.getModuleId())).findFirst().orElse(AlgorithmCatalog.MODULES.getFirst());
        if (!module.equals(modules.getSelectionModel().getSelectedItem())) modules.getSelectionModel().select(module);
        var algorithm = AlgorithmCatalog.ALGORITHMS.get(project.getAlgorithmId());
        if (algorithm != null) {
            if (!algorithms.getItems().contains(algorithm)) algorithms.getItems().setAll(module.algorithms().stream().map(AlgorithmCatalog.ALGORITHMS::get).toList());
            algorithms.getSelectionModel().select(algorithm);
            renderParameterControls(algorithm, project);
        }
        rendering = false;
        updateSpectrumControls(project);
        updateCursorControls(project);
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
    private record PointRow(int index, double x, double y, String series) { }
    private record HarmonicRow(int number, double frequency, double amplitude) { }
    private record CursorRow(double x, double y) { }

    @Override public void stop() {
        if (mcp != null) mcp.close();
        if (worker != null) worker.shutdownNow();
    }
}
