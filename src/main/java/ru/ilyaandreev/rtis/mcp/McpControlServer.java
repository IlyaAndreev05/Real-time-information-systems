package ru.ilyaandreev.rtis.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import ru.ilyaandreev.rtis.core.AlgorithmCatalog;
import ru.ilyaandreev.rtis.core.AdcModel;
import ru.ilyaandreev.rtis.core.AutocorrelationModels;
import ru.ilyaandreev.rtis.core.AnalysisEngine;
import ru.ilyaandreev.rtis.core.ParameterSpec;
import ru.ilyaandreev.rtis.core.SignalMath;
import ru.ilyaandreev.rtis.export.ChartRenderer;
import ru.ilyaandreev.rtis.model.ExperimentResult;
import ru.ilyaandreev.rtis.model.LabProject;
import ru.ilyaandreev.rtis.model.Point;
import ru.ilyaandreev.rtis.storage.ProjectSession;
import ru.ilyaandreev.rtis.storage.ProjectStore;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/** MCP adapter; GUI and agent operations share the same ProjectSession and calculation engine. */
public final class McpControlServer implements AutoCloseable {
    private final ProjectSession session;
    private final ProjectStore store;
    private final Supplier<byte[]> uiSnapshot;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final ExecutorService calculations = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "rtis-mcp-calculation");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Future<?>> jobs = new ConcurrentHashMap<>();
    private final Map<String, String> jobStates = new ConcurrentHashMap<>();
    private final Map<String, ExperimentResult> jobResults = new ConcurrentHashMap<>();
    private McpSyncServer mcpServer;
    private Server httpServer;

    public McpControlServer(ProjectSession session, ProjectStore store, Supplier<byte[]> uiSnapshot) {
        this.session = session;
        this.store = store;
        this.uiSnapshot = uiSnapshot;
    }

    public void startStdio() {
        StdioServerTransportProvider transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        mcpServer = buildServer(McpServer.sync(transport));
    }

    public void startHttp(int port) throws Exception {
        HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(McpJsonDefaults.getMapper()).mcpEndpoint("/mcp").build();
        mcpServer = buildServer(McpServer.sync(transport));
        httpServer = new Server(new java.net.InetSocketAddress("127.0.0.1", port));
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(transport), "/mcp");
        httpServer.setHandler(context);
        httpServer.start();
    }

    private McpSyncServer buildServer(McpServer.SyncSpecification<?> builder) {
        builder.serverInfo("rtis-lab-suite", "0.1.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build());
        add(builder, "catalog", "Список лабораторных модулей, алгоритмов, происхождения и статусов.", schema(Map.of()), args -> catalog());
        add(builder, "project_get", "Текущее состояние проекта и ревизия входных данных.", schema(Map.of()), args -> projectSummary());
        add(builder, "project_new", "Создать новый пустой проект.", schema(Map.of("name", stringProperty("Название проекта"))), args -> newProject(str(args, "name", "Новый проект")));
        add(builder, "project_set_signal", "Задать сигнал [{x,y}, ...]. Для редких узловых значений используйте mode=controlPoints, чтобы интерполяция прошла через каждую точку.", schema(Map.of(
            "points", arrayProperty("Координаты точек сигнала"),
            "mode", enumStringProperty("samples — дискретные отсчёты; controlPoints — опорные узлы интерполяции"))), args -> setPoints(args));
        add(builder, "project_point_add", "Добавить одну опорную точку в отсортированный сигнал.", schema(Map.of(
            "x", numberProperty("Координата времени"), "y", numberProperty("Амплитуда"))), args -> addPoint(args));
        add(builder, "project_point_update", "Изменить точку исходного сигнала по индексу; список после изменения сортируется по x.", schema(Map.of(
            "index", integerProperty("Индекс точки, начиная с нуля"), "x", numberProperty("Новая координата времени"),
            "y", numberProperty("Новая амплитуда"))), args -> updatePoint(args));
        add(builder, "project_point_delete", "Удалить точку исходного сигнала по индексу, начиная с нуля.", schema(Map.of(
            "index", integerProperty("Индекс точки, начиная с нуля"))), args -> deletePoint(args));
        add(builder, "project_generate_sine", "Сформировать гармонический тестовый сигнал.", schema(Map.of(
            "amplitude", numberProperty("Амплитуда"), "frequency", numberProperty("Частота в герцах"),
            "phase", numberProperty("Начальная фаза в радианах"), "duration", numberProperty("Длительность в секундах"),
            "count", integerProperty("Число отсчётов"))), args -> generateSine(args));
        add(builder, "project_generate_multitone", "Сформировать суммарный сигнал из гармоник; каждая тройка задаёт амплитуду, частоту в герцах и фазу в радианах.", schema(Map.of(
            "duration", numberProperty("Длительность в секундах"), "count", integerProperty("Число отсчётов"),
            "components", numericArrayProperty("Плоский массив троек amplitude, frequencyHz, phaseRad"))), args -> generateMultitone(args));
        add(builder, "project_generate_distribution", "Сформировать воспроизводимую seeded реализацию uniform или normal на указанном диапазоне.", schema(Map.of(
            "duration", numberProperty("Длительность в секундах"), "count", integerProperty("Число отсчётов"),
            "min", numberProperty("Нижний предел амплитуды"), "max", numberProperty("Верхний предел амплитуды"),
            "law", stringEnumProperty("uniform или normal", List.of("uniform", "normal")), "seed", integerProperty("Целочисленный seed"))), args -> generateDistribution(args));
        add(builder, "adc_conversion", "Выполнить и сохранить текущий опыт дискретизации/АЦП/ЦАП с метриками относительно исходного сигнала.", schema(Map.of(
            "sampleInterval", numberProperty("Интервал дискретизации, с"), "bits", integerProperty("Разрядность, 1–24"),
            "fullScaleMin", numberProperty("Нижний предел шкалы"), "fullScaleMax", numberProperty("Верхний предел шкалы"),
            "reconstruction", stringEnumProperty("step или linear", List.of("step", "linear")),
            "errorType", stringEnumProperty("Модель инструментальной ошибки", List.of("none", "additive", "multiplicative", "integral", "differential", "combined")),
            "errorPercent", numberProperty("Ошибка в процентах; DNL — от полной шкалы, combined — суммарная ошибка"),
            "differentialBit", integerProperty("Индекс одного из трёх старших битов: 0–2"))), args -> adcConversion(args));
        add(builder, "adc_sweep", "Рассчитать серии дискретизации для списка разрядностей и интервалов; вернуть таблицу с f, B=n·f, СКО и MAX.", schema(Map.of(
            "sampleIntervals", numericArrayProperty("Интервалы дискретизации в секундах"), "bits", numericArrayProperty("Список разрядностей в битах"),
            "fullScaleMin", numberProperty("Нижний предел шкалы"), "fullScaleMax", numberProperty("Верхний предел шкалы"),
            "reconstruction", stringEnumProperty("step или linear", List.of("step", "linear")),
            "errorType", stringEnumProperty("Модель инструментальной ошибки", List.of("none", "additive", "multiplicative", "integral", "differential", "combined")),
            "errorPercent", numberProperty("Ошибка в процентах; DNL — от полной шкалы, combined — суммарная ошибка"),
            "differentialBit", integerProperty("Индекс одного из трёх старших битов: 0–2"))), args -> adcSweep(args));
        add(builder, "experiment_grid", "Перебрать декартово произведение сеток параметров текущего сигнала; вернуть метрики каждого запуска и сохранить лучший проект/CSV/PNG.", schema(Map.of(
            "moduleId", stringProperty("Идентификатор модуля"), "algorithmId", stringProperty("Идентификатор алгоритма"),
            "axes", numberGridProperty("Сетки параметров по идентификатору параметра"),
            "criterion", stringProperty("Метрика минимума; по умолчанию среднеквадратичнаяОшибка"),
            "outputDirectory", stringProperty("Папка для JSON, CSV, лучшего JSON-проекта и PNG; можно не задавать"),
            "saveAllRuns", Map.of("type", "boolean", "description", "При outputDirectory сохранить проект и численные CSV каждого сочетания параметров"),
            "saveRunPlots", Map.of("type", "boolean", "description", "При saveAllRuns сохранить PNG каждого опыта"))), args -> experimentGrid(args));
        add(builder, "acf_model_sweep", "Подобрать alpha для десяти теоретических моделей АКФ, сравнить с центрированной несмещённой эмпирической АКФ и сохранить таблицы/график.", schema(Map.of(
            "maxLagFraction", numberProperty("Доля диапазона лагов от 0 до 1; по умолчанию 0.5"),
            "alphaMin", numberProperty("Нижняя граница поиска alpha, с⁻¹; по умолчанию 0.01"),
            "alphaMax", numberProperty("Верхняя граница поиска alpha, с⁻¹; по умолчанию 1000"),
            "gridSteps", integerProperty("Шаги логарифмического поиска, 20–10000; по умолчанию 500"),
            "outputDirectory", stringProperty("Каталог JSON/CSV/PNG; можно не задавать"))), args -> autocorrelationModelSweep(args));
        add(builder, "compression_knots_export", "Экспортировать переданные квантованные узлы текущего опыта сжатия в CSV.", schema(Map.of(
            "path", stringProperty("Путь к CSV файлу узлов"))), args -> exportCompressionKnots(args));
        add(builder, "project_set_analysis", "Выбрать модуль, алгоритм и параметры расчёта.", schema(Map.of(
            "moduleId", stringProperty("Идентификатор модуля"), "algorithmId", stringProperty("Идентификатор алгоритма"),
            "parameters", objectProperty("Числовые параметры алгоритма"))), args -> setAnalysis(args));
        add(builder, "experiment_start", "Запустить расчёт асинхронно; возвращает jobId.", schema(Map.of()), args -> startExperiment());
        add(builder, "job_get", "Получить состояние и результат расчёта.", schema(Map.of("jobId", stringProperty("Идентификатор расчёта"))), args -> getJob(args));
        add(builder, "job_cancel", "Отменить выполняемый расчёт.", schema(Map.of("jobId", stringProperty("Идентификатор расчёта"))), args -> cancelJob(args));
        add(builder, "results_page", "Получить страницу точек текущего результата; не более 2000 точек за вызов.", schema(Map.of(
            "offset", integerProperty("Начальный индекс"), "limit", integerProperty("Число точек"))), args -> resultsPage(args));
        add(builder, "table_page", "Постранично прочитать таблицу координат, гармоник или нулевых пересечений.", schema(Map.of(
            "table", stringProperty("input, result, harmonics или zeroCrossings"),
            "offset", integerProperty("Начальный индекс"), "limit", integerProperty("Число строк; максимум 2000"))), args -> tablePage(args));
        add(builder, "spectrum_range_set", "Задать спектральный диапазон по номерам гармоник. При lockTwelveHarmonics=true ширина равна 12, насколько позволяет спектр.", schema(Map.of(
            "lowHarmonic", integerProperty("Нижняя гармоника"), "highHarmonic", integerProperty("Верхняя гармоника"),
            "lockTwelveHarmonics", Map.of("type", "boolean", "description", "Фиксировать ширину в 12 гармоник"))), args -> setSpectrumRange(args));
        add(builder, "spectrum_harmonic_select", "Выбрать гармонику и вернуть её частоту и амплитуду из рассчитанного спектра.", schema(Map.of(
            "harmonic", integerProperty("Номер гармоники"))), args -> selectHarmonic(args));
        add(builder, "spectrum_notch_set", "Сохранить состояние переключателя фильтра «Пробка» в проекте.", schema(Map.of(
            "enabled", Map.of("type", "boolean", "description", "Включить или выключить переключатель"))), args -> setNotchFilter(args));
        add(builder, "measurement_cursor_set", "Переместить измерительный курсор по сигналу и вернуть интерполированную координату.", schema(Map.of(
            "x", numberProperty("Координата курсора"))), args -> setMeasurementCursor(args));
        add(builder, "state_export", "Сохранить проект в собственном JSON-формате по указанному пути.", schema(Map.of("path", stringProperty("Путь файла JSON"))), args -> exportState(args));
        add(builder, "state_import", "Импортировать проект JSON; повреждённый файл не заменяет открытый проект.", schema(Map.of("path", stringProperty("Путь файла JSON"))), args -> importState(args));
        add(builder, "chart_export", "Получить PNG текущего графика как MCP image content.", schema(Map.of(
            "width", integerProperty("Ширина, 200–8192"), "height", integerProperty("Высота, 150–8192"))), args -> exportChart(args));
        add(builder, "coordinates_export", "Сохранить координаты исходного сигнала или результата как CSV.", schema(Map.of(
            "path", stringProperty("Путь экспорта"), "series", stringProperty("input или result"),
            "format", stringProperty("csv или json"))), args -> exportCoordinates(args));
        add(builder, "ui_snapshot", "Снимок открытого GUI с текущим графиком. Доступен, когда окно открыто.", schema(Map.of()), args -> snapshotUi());
        return builder.build();
    }

    @FunctionalInterface private interface Operation { Object run(Map<String, Object> args) throws Exception; }

    private void add(McpServer.SyncSpecification<?> server, String name, String description,
                     Map<String, Object> inputSchema, Operation operation) {
        McpSchema.Tool tool = McpSchema.Tool.builder(name, inputSchema).description(description).build();
        server.toolCall(tool, (exchange, request) -> {
            try {
                Object value = operation.run(request.arguments());
                if (value instanceof McpSchema.CallToolResult result) return result;
                return textResult(mapper.writeValueAsString(value), false);
            } catch (Exception e) {
                return textResult("Ошибка: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), true);
            }
        });
    }

    private Object catalog() {
        return Map.of("modules", AlgorithmCatalog.MODULES, "algorithms", AlgorithmCatalog.ALGORITHMS.values(),
            "signalInputModes", List.of(Map.of("id", "samples", "description", "Обычная последовательность дискретных отсчётов; шаг параметра stride применяется."),
                Map.of("id", "controlPoints", "description", "Редкие опорные точки; режимы интерполяции используют все узлы и строят плотный результат через них.")));
    }

    private Object projectSummary() {
        LabProject p = session.snapshot();
        return Map.of("project", p, "revision", session.revision(), "latestJob", List.copyOf(jobs.keySet()));
    }

    private Object newProject(String name) throws IOException {
        session.update(old -> {
            LabProject p = new LabProject();
            p.setName(name);
            return p;
        });
        return projectSummary();
    }

    private Object setPoints(Map<String, Object> args) throws IOException {
        Object raw = args.get("points");
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("points должен быть массивом координат");
        List<Point> points = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException("Каждая точка должна содержать x и y");
            points.add(new Point(number(map.get("x")), number(map.get("y"))));
        }
        SignalMath.requireSignal(points);
        String mode = str(args, "mode", "samples");
        session.update(p -> { p.setInput(points); p.setInputKind(mode); return p; });
        return Map.of("pointCount", points.size(), "mode", mode, "revision", session.revision());
    }

    private Object addPoint(Map<String, Object> args) throws IOException {
        Point point = new Point(number(args.get("x")), number(args.get("y")));
        session.update(p -> {
            List<Point> points = new ArrayList<>(p.getInput());
            if (points.stream().anyMatch(existing -> existing.x() == point.x()))
                throw new IllegalArgumentException("В сигнале уже есть точка с такой координатой x");
            points.add(point);
            points.sort(java.util.Comparator.comparingDouble(Point::x));
            if (points.size() > 1) SignalMath.requireSignal(points);
            p.setInput(points); p.setInputKind("controlPoints"); return p;
        });
        return Map.of("pointCount", session.snapshot().getInput().size(), "revision", session.revision());
    }

    private Object updatePoint(Map<String, Object> args) throws IOException {
        int index = (int) number(args.get("index"));
        Point replacement = new Point(number(args.get("x")), number(args.get("y")));
        session.update(p -> {
            List<Point> points = new ArrayList<>(p.getInput());
            if (index < 0 || index >= points.size()) throw new IllegalArgumentException("Индекс точки вне диапазона");
            points.set(index, replacement);
            points.sort(java.util.Comparator.comparingDouble(Point::x));
            if (points.size() > 1) SignalMath.requireSignal(points);
            p.setInput(points); return p;
        });
        return Map.of("pointCount", session.snapshot().getInput().size(), "revision", session.revision());
    }

    private Object deletePoint(Map<String, Object> args) throws IOException {
        int index = (int) number(args.get("index"));
        session.update(p -> {
            List<Point> points = new ArrayList<>(p.getInput());
            if (index < 0 || index >= points.size()) throw new IllegalArgumentException("Индекс точки вне диапазона");
            points.remove(index); p.setInput(points); return p;
        });
        return Map.of("pointCount", session.snapshot().getInput().size(), "revision", session.revision());
    }

    private Object generateSine(Map<String, Object> args) throws IOException {
        double amplitude = number(args.get("amplitude"));
        double frequency = number(args.get("frequency"));
        double phase = number(args.get("phase"));
        double duration = number(args.get("duration"));
        int count = integerNumber(args.get("count"));
        List<Point> points = SignalMath.sine(amplitude, frequency, phase, duration, count);
        session.update(p -> { p.setInput(points); p.setInputKind("samples"); return p; });
        return Map.of("pointCount", points.size(), "revision", session.revision());
    }

    private Object generateMultitone(Map<String, Object> args) throws IOException {
        Object raw = args.get("components");
        if (!(raw instanceof List<?> values) || values.isEmpty() || values.size() % 3 != 0)
            throw new IllegalArgumentException("components должен быть массивом троек амплитуда, частота, фаза");
        double[] components = new double[values.size()];
        for (int i = 0; i < values.size(); i++) components[i] = number(values.get(i));
        List<Point> points = SignalMath.multiTone(number(args.get("duration")), integerNumber(args.get("count")), components);
        session.update(p -> { p.setInput(points); p.setInputKind("samples"); p.setNoiseSeed(4319L); return p; });
        return Map.of("pointCount", points.size(), "components", components.length / 3, "revision", session.revision());
    }

    private Object generateDistribution(Map<String, Object> args) throws IOException {
        long seed = (long) number(args.get("seed"));
        String law = str(args, "law", "");
        List<Point> points = SignalMath.distribution(number(args.get("duration")), integerNumber(args.get("count")),
            number(args.get("min")), number(args.get("max")), law, seed);
        session.update(p -> { p.setInput(points); p.setInputKind("samples"); p.setNoiseSeed(seed); return p; });
        double actualMin = points.stream().mapToDouble(Point::y).min().orElseThrow();
        double actualMax = points.stream().mapToDouble(Point::y).max().orElseThrow();
        return Map.of("pointCount", points.size(), "law", law, "seed", seed,
            "requestedMin", number(args.get("min")), "requestedMax", number(args.get("max")),
            "actualMin", actualMin, "actualMax", actualMax, "actualRange", actualMax - actualMin,
            "revision", session.revision());
    }

    private Object adcConversion(Map<String, Object> args) throws IOException {
        LabProject current = session.snapshot();
        if (current.getInput().isEmpty()) throw new IllegalStateException("Сначала задайте исходный сигнал");
        String reconstruction = str(args, "reconstruction", "step");
        String errorType = str(args, "errorType", "none");
        double interval = number(args.get("sampleInterval"));
        int bits = integerNumber(args.get("bits"));
        double min = number(args.get("fullScaleMin")), max = number(args.get("fullScaleMax"));
        double error = number(args.get("errorPercent"));
        int differentialBit = integerNumber(args.getOrDefault("differentialBit", 0));
        AdcModel.Conversion conversion = AdcModel.convert(current.getInput(), interval, bits, min, max,
            reconstruction, errorType, error, differentialBit);
        Map<String, Double> params = new HashMap<>();
        params.put("sampleInterval", interval); params.put("bits", (double) bits);
        params.put("fullScaleMin", min); params.put("fullScaleMax", max);
        params.put("errorPercent", error); params.put("differentialBit", (double) differentialBit);
        params.put("errorMode", (double) List.of("none", "additive", "multiplicative", "integral", "differential", "combined").indexOf(errorType));
        params.put("reconstructionMode", "linear".equals(reconstruction) ? 1.0 : 0.0);
        session.update(p -> { p.setModuleId("conversion"); p.setAlgorithmId("adc-dac"); p.setParameters(params); return p; });
        long revision = session.revision();
        ExperimentResult result = new ExperimentResult(UUID.randomUUID().toString(), "conversion", "adc-dac", revision,
            conversion.reconstructed(), conversion.metrics());
        session.publishResult(result);
        return Map.of("resultId", result.resultId(), "revision", revision, "metrics", conversion.metrics(),
            "sampleCount", conversion.samples().size(), "reconstructedCount", conversion.reconstructed().size());
    }

    private Object adcSweep(Map<String, Object> args) {
        List<Point> source = session.snapshot().getInput();
        if (source.size() < 2) throw new IllegalStateException("Сначала задайте исходный сигнал");
        List<?> intervals = requireList(args.get("sampleIntervals"), "sampleIntervals");
        List<?> bitValues = requireList(args.get("bits"), "bits");
        if ((long) intervals.size() * bitValues.size() > 5000) throw new IllegalArgumentException("Серия ограничена 5000 сочетаниями");
        String reconstruction = str(args, "reconstruction", "step");
        String errorType = str(args, "errorType", "none");
        double min = number(args.get("fullScaleMin")), max = number(args.get("fullScaleMax"));
        double error = number(args.get("errorPercent"));
        int differentialBit = integerNumber(args.getOrDefault("differentialBit", 0));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object bitValue : bitValues) for (Object intervalValue : intervals) {
            int bits = integerNumber(bitValue); double interval = number(intervalValue);
            AdcModel.Conversion conversion = AdcModel.convert(source, interval, bits, min, max,
                reconstruction, errorType, error, differentialBit);
            Map<String, Object> row = new HashMap<>(conversion.metrics());
            row.put("bits", bits); row.put("sampleIntervalSeconds", interval);
            row.put("samplingFrequencyHz", 1 / interval); row.put("informationRateBitsPerSecond", bits / interval);
            rows.add(Map.copyOf(row));
        }
        return Map.of("count", rows.size(), "rows", rows, "criterionDescription", "RMSE/MAX измерены относительно исходного сигнала на его временной сетке");
    }

    private Object experimentGrid(Map<String, Object> args) throws Exception {
        LabProject baseline = session.snapshot();
        if (baseline.getInput().size() < 2) throw new IllegalStateException("Сначала задайте исходный сигнал");
        String moduleId = str(args, "moduleId", ""), algorithmId = str(args, "algorithmId", "");
        AlgorithmCatalog.Algorithm definition = AlgorithmCatalog.ALGORITHMS.get(algorithmId);
        if (definition == null || !definition.moduleId().equals(moduleId)) throw new IllegalArgumentException("Алгоритм не принадлежит указанному модулю");
        Object axesValue = args.get("axes");
        if (!(axesValue instanceof Map<?, ?> rawAxes) || rawAxes.isEmpty()) throw new IllegalArgumentException("axes должен задавать хотя бы одну сетку параметров");
        LinkedHashMap<String, List<Double>> axes = new LinkedHashMap<>();
        long count = 1;
        for (Map.Entry<?, ?> entry : rawAxes.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof List<?> values) || values.isEmpty()) throw new IllegalArgumentException("Сетка " + key + " должна быть непустым массивом чисел");
            List<Double> valuesAsDoubles = values.stream().map(McpControlServer::number).toList();
            count *= valuesAsDoubles.size();
            if (count > 25000) throw new IllegalArgumentException("Сетка ограничена 25000 сочетаниями");
            axes.put(key, valuesAsDoubles);
        }
        String criterion = str(args, "criterion", "среднеквадратичнаяОшибка");
        Map<String, ParameterSpec> specs = new HashMap<>();
        definition.parameters().forEach(parameter -> specs.put(parameter.id(), parameter));
        for (String key : axes.keySet()) if (!specs.containsKey(key)) throw new IllegalArgumentException("Параметр " + key + " неизвестен алгоритму " + algorithmId);
        List<Map<String, Object>> rows = new ArrayList<>((int) count);
        double best = Double.POSITIVE_INFINITY;
        Map<String, Double> bestParameters = Map.of();
        ExperimentResult bestResult = null;
        int runIndex = 0;
        String outputDirectory = str(args, "outputDirectory", "");
        boolean saveAllRuns = bool(args.getOrDefault("saveAllRuns", false));
        boolean saveRunPlots = bool(args.getOrDefault("saveRunPlots", false));
        if (saveRunPlots && !saveAllRuns) throw new IllegalArgumentException("saveRunPlots требует saveAllRuns=true");
        Path runRoot = outputDirectory.isBlank() ? null : Path.of(outputDirectory).toAbsolutePath();
        for (Map<String, Double> supplied : cartesian(axes)) {
            Map<String, Double> parameters = normalizeParameters(definition, supplied);
            LabProject trial = baseline.copy();
            trial.setModuleId(moduleId); trial.setAlgorithmId(algorithmId); trial.setParameters(parameters);
            ExperimentResult result = AnalysisEngine.run(trial, session.revision(), () -> Thread.currentThread().isInterrupted());
            Double score = result.metrics().get(criterion);
            if (score == null || !Double.isFinite(score)) throw new IllegalArgumentException("Метрика " + criterion + " отсутствует в результате " + algorithmId);
            int thisRun = ++runIndex;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("run", thisRun); row.put("algorithmId", algorithmId); row.put("parameters", parameters); row.put("metrics", result.metrics());
            row.put("inputPointCount", baseline.getInput().size()); row.put("noiseSeed", baseline.getNoiseSeed());
            row.put("programVersion", "0.1.0");
            rows.add(Map.copyOf(row));
            if (saveAllRuns) saveGridRun(runRoot, trial, result, thisRun, saveRunPlots);
            if (score < best) { best = score; bestParameters = parameters; bestResult = result; }
        }
        if (bestResult == null) throw new IllegalStateException("Сетка не создала результатов");
        final Map<String, Double> selectedParameters = bestParameters;
        session.update(p -> { p.setModuleId(moduleId); p.setAlgorithmId(algorithmId); p.setParameters(selectedParameters); return p; });
        long revision = session.revision();
        ExperimentResult published = new ExperimentResult(bestResult.resultId(), moduleId, algorithmId, revision,
            bestResult.points(), bestResult.metrics());
        session.publishResult(published);
        if (!outputDirectory.isBlank()) writeGridArtifacts(Path.of(outputDirectory), algorithmId, criterion,
            rows, session.snapshot());
        return Map.of("count", rows.size(), "criterion", criterion, "bestValue", best,
            "bestParameters", bestParameters, "bestMetrics", bestResult.metrics(), "revision", revision,
            "rows", rows);
    }

    private void saveGridRun(Path root, LabProject trial, ExperimentResult result, int run, boolean savePlot) throws IOException {
        if (root == null) throw new IllegalArgumentException("saveAllRuns требует outputDirectory");
        String id = String.format(java.util.Locale.ROOT, "run-%05d", run);
        Path states = root.resolve("states"), data = root.resolve("data"), plots = root.resolve("plots");
        java.nio.file.Files.createDirectories(states); java.nio.file.Files.createDirectories(data);
        trial.setName(trial.getModuleId() + "-" + trial.getAlgorithmId() + "-" + id);
        trial.setResult(result.points()); trial.setMetrics(result.metrics());
        store.save(states.resolve(id + ".json"), trial);
        java.nio.file.Files.writeString(data.resolve(id + "-input.csv"), ChartRenderer.csv(trial.getInput()), StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(data.resolve(id + "-output.csv"), ChartRenderer.csv(result.points()), StandardCharsets.UTF_8);
        if (trial.getAlgorithmId().startsWith("compression-") || trial.getAlgorithmId().startsWith("extrapolation-")) {
            java.nio.file.Files.writeString(data.resolve(id + "-knots.csv"), ChartRenderer.csv(AnalysisEngine.compressionKnots(trial)), StandardCharsets.UTF_8);
        }
        if (savePlot) {
            java.nio.file.Files.createDirectories(plots);
            java.nio.file.Files.write(plots.resolve(id + ".png"), ChartRenderer.png(trial, 1200, 760));
        }
    }

    private Object autocorrelationModelSweep(Map<String, Object> args) throws IOException {
        LabProject sourceProject = session.snapshot();
        if (sourceProject.getInput().size() < 20) throw new IllegalStateException("Для подбора моделей задайте не менее 20 отсчётов");
        double maxLagFraction = number(args.getOrDefault("maxLagFraction", 0.5));
        double alphaMin = number(args.getOrDefault("alphaMin", 0.01));
        double alphaMax = number(args.getOrDefault("alphaMax", 1000));
        int gridSteps = integerNumber(args.getOrDefault("gridSteps", 500));
        AutocorrelationModels.Fit fit = AutocorrelationModels.fit(sourceProject.getInput(), maxLagFraction, alphaMin, alphaMax, gridSteps);
        AutocorrelationModels.ModelFit bestByRmse = fit.models().stream()
            .min(java.util.Comparator.comparingDouble(AutocorrelationModels.ModelFit::rmse)
                .thenComparingDouble(AutocorrelationModels.ModelFit::maxAbsoluteError)).orElseThrow();
        AutocorrelationModels.ModelFit best = fit.minimaxModels().stream()
            .min(java.util.Comparator.comparingDouble(AutocorrelationModels.ModelFit::maxAbsoluteError)
                .thenComparingDouble(AutocorrelationModels.ModelFit::rmse)).orElseThrow();
        List<Map<String, Object>> rows = fit.models().stream().map(model -> {
            AutocorrelationModels.ModelFit minimax = fit.minimaxModels().get(model.model() - 1);
            return Map.<String, Object>of("model", model.model(),
                "alphaForRMSEPerSecond", model.alpha(), "rmseAtAlphaForRMSE", model.rmse(), "maxAtAlphaForRMSE", model.maxAbsoluteError(),
                "alphaForMAXPerSecond", minimax.alpha(), "rmseAtAlphaForMAX", minimax.rmse(), "minimumMAX", minimax.maxAbsoluteError());
        }).toList();
        session.update(p -> {
            p.setModuleId("spectrum"); p.setAlgorithmId("autocorrelation");
            p.setInput(fit.empirical()); p.setInputKind("samples"); p.setResult(best.curve());
            p.setParameters(Map.of("fittedModel", (double) best.model(), "alphaPerSecond", best.alpha()));
            p.setMetrics(Map.of("rmseNormalized", best.rmse(), "maxAbsoluteErrorNormalized", best.maxAbsoluteError()));
            p.setChartTitle("Эмпирическая АКФ и лучшая теоретическая модель №" + best.model());
            p.setXAxisLabel("Лаг τ, с"); p.setYAxisLabel("Нормированная АКФ"); return p;
        });
        long revision = session.revision();
        session.publishResult(new ExperimentResult(UUID.randomUUID().toString(), "spectrum", "autocorrelation",
            revision, best.curve(), Map.of("rmseNormalized", best.rmse(), "maxAbsoluteErrorNormalized", best.maxAbsoluteError())));
        String directoryName = str(args, "outputDirectory", "");
        String outputDirectory = "";
        if (!directoryName.isBlank()) {
            Path directory = Path.of(directoryName).toAbsolutePath();
            java.nio.file.Files.createDirectories(directory);
            store.save(directory.resolve("source-project.json"), sourceProject);
            store.save(directory.resolve("best-fit-project.json"), session.snapshot());
            mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("model-fits.json").toFile(), rows);
            writeAcfCurves(directory.resolve("acf-model-curves.csv"), fit.empirical(), fit.minimaxModels());
            writeAcfCurves(directory.resolve("acf-model-curves-rmse.csv"), fit.empirical(), fit.models());
            List<ChartRenderer.NamedSeries> maxCurves = new ArrayList<>();
            List<ChartRenderer.NamedSeries> rmseCurves = new ArrayList<>();
            maxCurves.add(new ChartRenderer.NamedSeries("Эмпирическая АКФ", fit.empirical()));
            rmseCurves.add(new ChartRenderer.NamedSeries("Эмпирическая АКФ", fit.empirical()));
            for (AutocorrelationModels.ModelFit model : fit.minimaxModels())
                maxCurves.add(new ChartRenderer.NamedSeries("Модель " + model.model() + " (α по MAX)", model.curve()));
            for (AutocorrelationModels.ModelFit model : fit.models())
                rmseCurves.add(new ChartRenderer.NamedSeries("Модель " + model.model() + " (α по СКО)", model.curve()));
            java.nio.file.Files.write(directory.resolve("acf-model-comparison.png"), ChartRenderer.comparisonPng(
                "Эмпирическая АКФ и 10 моделей: minimax MAX", "Лаг τ, с", "Нормированная АКФ", maxCurves, 1800, 1100));
            java.nio.file.Files.write(directory.resolve("acf-model-comparison-rmse.png"), ChartRenderer.comparisonPng(
                "Эмпирическая АКФ и 10 моделей: min RMSE", "Лаг τ, с", "Нормированная АКФ", rmseCurves, 1800, 1100));
            java.nio.file.Files.write(directory.resolve("best-fit.png"), ChartRenderer.png(session.snapshot(), 1600, 1000));
            outputDirectory = directory.toString();
        }
        return Map.of("count", rows.size(), "models", rows,
            "bestByMAX", Map.of("model", best.model(), "alphaPerSecond", best.alpha(),
                "rmseNormalized", best.rmse(), "maxAbsoluteErrorNormalized", best.maxAbsoluteError()),
            "bestByRMSE", Map.of("model", bestByRmse.model(), "alphaPerSecond", bestByRmse.alpha(),
                "rmseNormalized", bestByRmse.rmse(), "maxAbsoluteErrorNormalized", bestByRmse.maxAbsoluteError()),
            "empiricalLagCount", fit.empirical().size(), "revision", revision,
            "outputDirectory", outputDirectory);
    }

    private static void writeAcfCurves(Path destination, List<Point> empirical,
                                       List<AutocorrelationModels.ModelFit> models) throws IOException {
        StringBuilder csv = new StringBuilder("lagSeconds,empirical");
        for (int model = 1; model <= 10; model++) csv.append(",model").append(model);
        csv.append('\n');
        for (int i = 0; i < empirical.size(); i++) {
            csv.append(empirical.get(i).x()).append(',').append(empirical.get(i).y());
            for (AutocorrelationModels.ModelFit model : models) csv.append(',').append(model.curve().get(i).y());
            csv.append('\n');
        }
        java.nio.file.Files.writeString(destination, csv.toString(), StandardCharsets.UTF_8);
    }

    private static List<Map<String, Double>> cartesian(LinkedHashMap<String, List<Double>> axes) {
        List<Map<String, Double>> rows = new ArrayList<>();
        rows.add(new LinkedHashMap<>());
        for (Map.Entry<String, List<Double>> axis : axes.entrySet()) {
            List<Map<String, Double>> expanded = new ArrayList<>(rows.size() * axis.getValue().size());
            for (Map<String, Double> row : rows) for (double value : axis.getValue()) {
                Map<String, Double> next = new LinkedHashMap<>(row); next.put(axis.getKey(), value); expanded.add(next);
            }
            rows = expanded;
        }
        return rows;
    }

    private void writeGridArtifacts(Path directory, String algorithmId, String criterion,
                                    List<Map<String, Object>> rows, LabProject bestProject) throws IOException {
        Path target = directory.toAbsolutePath();
        java.nio.file.Files.createDirectories(target);
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve("grid.json").toFile(), rows);
        store.save(target.resolve("best-project.json"), bestProject);
        ChartRenderer.savePng(bestProject, target.resolve("best.png"), 1600, 1000);
        java.nio.file.Files.writeString(target.resolve("best-input.csv"), ChartRenderer.csv(bestProject.getInput()), StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(target.resolve("best-output.csv"), ChartRenderer.csv(bestProject.getResult()), StandardCharsets.UTF_8);
        if (algorithmId.startsWith("compression-") || algorithmId.startsWith("extrapolation-"))
            java.nio.file.Files.writeString(target.resolve("best-knots.csv"), ChartRenderer.csv(AnalysisEngine.compressionKnots(bestProject)), StandardCharsets.UTF_8);
        List<String> parameterNames = new ArrayList<>(rows.getFirst().get("parameters") instanceof Map<?, ?> map
            ? map.keySet().stream().map(String::valueOf).toList() : List.of());
        List<String> metricNames = new ArrayList<>(((Map<?, ?>) rows.getFirst().get("metrics")).keySet().stream().map(String::valueOf).sorted().toList());
        StringBuilder csv = new StringBuilder("run,algorithmId");
        parameterNames.forEach(name -> csv.append(',').append(csvCell(name)));
        metricNames.forEach(name -> csv.append(',').append(csvCell(name)));
        csv.append('\n');
        for (Map<String, Object> row : rows) {
            csv.append(row.get("run")).append(',').append(csvCell(algorithmId));
            Map<?, ?> parameters = (Map<?, ?>) row.get("parameters"), metrics = (Map<?, ?>) row.get("metrics");
            for (String name : parameterNames) csv.append(',').append(parameters.get(name));
            for (String name : metricNames) csv.append(',').append(metrics.get(name));
            csv.append('\n');
        }
        java.nio.file.Files.writeString(target.resolve("grid.csv"), csv.toString(), StandardCharsets.UTF_8);
    }

    private static String csvCell(String value) {
        return value.matches("[A-Za-z0-9_]+") ? value : "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private Object setAnalysis(Map<String, Object> args) throws IOException {
        String module = str(args, "moduleId", "");
        String algorithm = str(args, "algorithmId", "");
        AlgorithmCatalog.Algorithm definition = AlgorithmCatalog.ALGORITHMS.get(algorithm);
        if (definition == null || !definition.moduleId().equals(module)) throw new IllegalArgumentException("Алгоритм не принадлежит выбранному модулю");
        Map<String, Double> supplied = new HashMap<>();
        Object raw = args.get("parameters");
        if (raw instanceof Map<?, ?> map) for (var entry : map.entrySet()) supplied.put(String.valueOf(entry.getKey()), number(entry.getValue()));
        Map<String, Double> params = normalizeParameters(definition, supplied);
        session.update(p -> { p.setModuleId(module); p.setAlgorithmId(algorithm); p.setParameters(params); return p; });
        return Map.of("moduleId", module, "algorithmId", algorithm, "parameters", params, "revision", session.revision());
    }

    private Map<String, Double> normalizeParameters(AlgorithmCatalog.Algorithm definition, Map<String, Double> supplied) {
        Map<String, ParameterSpec> specs = new HashMap<>();
        definition.parameters().forEach(spec -> specs.put(spec.id(), spec));
        for (String id : supplied.keySet()) if (!specs.containsKey(id))
            throw new IllegalArgumentException("Неизвестный параметр " + id + " для алгоритма " + definition.id());
        Map<String, Double> normalized = new HashMap<>();
        for (ParameterSpec spec : definition.parameters()) {
            double value = supplied.getOrDefault(spec.id(), spec.defaultValue());
            if (value < spec.min() || value > spec.max())
                throw new IllegalArgumentException(spec.name() + " должен быть от " + spec.min() + " до " + spec.max());
            if (spec.integer() && value != Math.rint(value)) throw new IllegalArgumentException(spec.name() + " должен быть целым числом");
            normalized.put(spec.id(), spec.clamp(value));
        }
        return normalized;
    }

    private Object startExperiment() {
        String id = UUID.randomUUID().toString();
        LabProject input = session.snapshot();
        long revision = session.revision();
        jobStates.put(id, "running");
        Future<?> future = calculations.submit(() -> {
            try {
                ExperimentResult result = AnalysisEngine.run(input, revision, () -> Thread.currentThread().isInterrupted());
                if (session.publishResult(result)) { jobResults.put(id, result); jobStates.put(id, "completed"); }
                else jobStates.put(id, "stale: входные данные изменились во время расчёта");
            } catch (java.util.concurrent.CancellationException e) {
                jobStates.put(id, "cancelled");
            } catch (Exception e) {
                jobStates.put(id, "failed: " + e.getMessage());
            }
        });
        jobs.put(id, future);
        return Map.of("jobId", id, "status", "running", "inputRevision", revision);
    }

    private Object getJob(Map<String, Object> args) {
        String id = str(args, "jobId", "");
        String state = jobStates.get(id);
        if (state == null) throw new IllegalArgumentException("Расчёт не найден");
        ExperimentResult result = jobResults.get(id);
        if ("completed".equals(state) && result != null) return Map.of("jobId", id, "status", state,
            "resultId", result.resultId(), "inputRevision", result.inputRevision(), "pointCount", result.points().size(), "metrics", result.metrics());
        return Map.of("jobId", id, "status", state, "updatedAt", Instant.now().toString());
    }

    private Object cancelJob(Map<String, Object> args) {
        String id = str(args, "jobId", "");
        Future<?> future = jobs.get(id);
        if (future == null) throw new IllegalArgumentException("Расчёт не найден");
        boolean cancelled = future.cancel(true);
        if (cancelled) jobStates.put(id, "cancelled");
        return Map.of("jobId", id, "cancelled", cancelled);
    }

    private Object resultsPage(Map<String, Object> args) {
        LabProject p = session.snapshot();
        int offset = Math.max(0, integerNumber(args.getOrDefault("offset", 0)));
        int limit = Math.min(2000, Math.max(1, integerNumber(args.getOrDefault("limit", 500))));
        List<Point> points = p.getResult().isEmpty() ? p.getInput() : p.getResult();
        int from = Math.min(offset, points.size()), to = Math.min(points.size(), from + limit);
        return Map.of("offset", from, "limit", limit, "total", points.size(), "points", points.subList(from, to), "metrics", p.getMetrics());
    }

    private Object tablePage(Map<String, Object> args) {
        LabProject project = session.snapshot();
        String table = str(args, "table", "input");
        int offset = Math.max(0, integerNumber(args.getOrDefault("offset", 0)));
        int limit = Math.min(2000, Math.max(1, integerNumber(args.getOrDefault("limit", 500))));
        List<Map<String, Object>> rows = new ArrayList<>();
        switch (table) {
            case "input" -> addPointRows(rows, project.getInput());
            case "result" -> addPointRows(rows, project.getResult());
            case "harmonics" -> {
                requireSpectrum(project);
                int low = Math.min(project.getSpectrumLowHarmonic(), project.getResult().size() - 1);
                int high = Math.min(Math.max(low, project.getSpectrumHighHarmonic()), project.getResult().size() - 1);
                for (int i = low; i <= high; i++) {
                    Point point = project.getResult().get(i);
                    rows.add(Map.of("harmonic", i, "frequencyHz", point.x(), "amplitude", point.y()));
                }
            }
            case "zeroCrossings" -> {
                for (Point point : SignalMath.zeroCrossings(project.getInput()))
                    rows.add(Map.of("x", point.x(), "y", point.y()));
            }
            case "cursor" -> {
                Point point = SignalMath.interpolateAt(project.getInput(), project.getMeasurementCursorX());
                rows.add(Map.of("x", point.x(), "y", point.y()));
            }
            default -> throw new IllegalArgumentException("Таблица должна быть input, result, harmonics, zeroCrossings или cursor");
        }
        int from = Math.min(offset, rows.size()), to = Math.min(rows.size(), from + limit);
        return Map.of("table", table, "offset", from, "limit", limit, "total", rows.size(), "rows", rows.subList(from, to));
    }

    private static void addPointRows(List<Map<String, Object>> rows, List<Point> points) {
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            rows.add(Map.of("index", i, "x", point.x(), "y", point.y()));
        }
    }

    private Object setSpectrumRange(Map<String, Object> args) throws IOException {
        LabProject current = session.snapshot();
        int max = current.getAlgorithmId().equals("fourier") && !current.getResult().isEmpty()
            ? current.getResult().size() - 1 : 100;
        int low = integerNumber(args.getOrDefault("lowHarmonic", current.getSpectrumLowHarmonic()));
        int high = integerNumber(args.getOrDefault("highHarmonic", current.getSpectrumHighHarmonic()));
        boolean locked = args.containsKey("lockTwelveHarmonics")
            ? bool(args.get("lockTwelveHarmonics")) : current.isLockTwelveHarmonics();
        if (low < 0 || high < 0 || low > max || high > max)
            throw new IllegalArgumentException("Границы должны быть от 0 до " + max);
        if (locked) {
            if (max >= 11 && low + 11 > max) low = max - 11;
            high = Math.min(max, low + 11);
        } else if (high < low) throw new IllegalArgumentException("Верхняя граница должна быть не ниже нижней");
        final int finalLow = low, finalHigh = high;
        session.updatePresentation(p -> {
            p.setSpectrumLowHarmonic(finalLow); p.setSpectrumHighHarmonic(finalHigh);
            p.setLockTwelveHarmonics(locked); return p;
        });
        return Map.of("lowHarmonic", finalLow, "highHarmonic", finalHigh,
            "lockTwelveHarmonics", locked, "revision", session.revision());
    }

    private Object selectHarmonic(Map<String, Object> args) throws IOException {
        LabProject project = session.snapshot();
        requireSpectrum(project);
        int harmonic = integerNumber(args.get("harmonic"));
        if (harmonic < 0 || harmonic >= project.getResult().size())
            throw new IllegalArgumentException("Гармоника должна быть от 0 до " + (project.getResult().size() - 1));
        session.updatePresentation(p -> { p.setSelectedHarmonic(harmonic); return p; });
        Point point = project.getResult().get(harmonic);
        return Map.of("harmonic", harmonic, "frequencyHz", point.x(), "amplitude", point.y(), "revision", session.revision());
    }

    private static void requireSpectrum(LabProject project) {
        if (!project.getAlgorithmId().equals("fourier") || project.getResult().isEmpty())
            throw new IllegalStateException("Сначала выберите ряд Фурье и рассчитайте амплитудный спектр");
    }

    private Object setNotchFilter(Map<String, Object> args) throws IOException {
        boolean enabled = bool(args.get("enabled"));
        session.updatePresentation(p -> { p.setNotchFilterEnabled(enabled); return p; });
        return Map.of("enabled", enabled, "revision", session.revision());
    }

    private Object setMeasurementCursor(Map<String, Object> args) throws IOException {
        double x = number(args.get("x"));
        Point measured = SignalMath.interpolateAt(session.snapshot().getInput(), x);
        session.updatePresentation(p -> { p.setMeasurementCursorX(x); return p; });
        return Map.of("x", measured.x(), "y", measured.y(), "revision", session.revision());
    }

    private Object exportState(Map<String, Object> args) throws IOException {
        Path path = Path.of(str(args, "path", ""));
        session.saveAs(path);
        return Map.of("path", path.toAbsolutePath().toString(), "bytes", java.nio.file.Files.size(path));
    }

    private Object importState(Map<String, Object> args) throws IOException {
        Path path = Path.of(str(args, "path", ""));
        session.replaceFrom(path);
        return Map.of("path", path.toAbsolutePath().toString(), "project", session.snapshot(), "revision", session.revision());
    }

    private Object exportChart(Map<String, Object> args) throws IOException {
        int width = (int) number(args.getOrDefault("width", 1200));
        int height = (int) number(args.getOrDefault("height", 800));
        byte[] image = ChartRenderer.png(session.snapshot(), width, height);
        return McpSchema.CallToolResult.builder(List.of(
            new McpSchema.TextContent("График, " + width + "×" + height + "."),
            new McpSchema.ImageContent(null, Base64.getEncoder().encodeToString(image), "image/png"))).build();
    }

    private Object exportCoordinates(Map<String, Object> args) throws IOException {
        LabProject p = session.snapshot();
        String series = str(args, "series", "result");
        List<Point> points = series.equals("input") ? p.getInput() : p.getResult();
        if (points.isEmpty()) throw new IllegalArgumentException("Выбранный ряд пока пуст");
        Path path = Path.of(str(args, "path", ""));
        if (path.toAbsolutePath().getParent() != null) java.nio.file.Files.createDirectories(path.toAbsolutePath().getParent());
        String format = str(args, "format", "csv").toLowerCase(java.util.Locale.ROOT);
        if (format.equals("csv")) java.nio.file.Files.writeString(path, ChartRenderer.csv(points), StandardCharsets.UTF_8);
        else if (format.equals("json")) java.nio.file.Files.write(path, mapper.writeValueAsBytes(points));
        else throw new IllegalArgumentException("Формат должен быть csv или json");
        return Map.of("path", path.toAbsolutePath().toString(), "series", series, "format", format, "pointCount", points.size());
    }

    private Object exportCompressionKnots(Map<String, Object> args) throws IOException {
        LabProject project = session.snapshot();
        List<Point> knots = AnalysisEngine.compressionKnots(project);
        Path path = Path.of(str(args, "path", "")).toAbsolutePath();
        if (path.getParent() != null) java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.writeString(path, ChartRenderer.csv(knots), StandardCharsets.UTF_8);
        return Map.of("path", path.toString(), "pointCount", knots.size());
    }

    private Object snapshotUi() throws IOException {
        byte[] image;
        try { image = uiSnapshot.get(); }
        catch (RuntimeException e) { throw new IOException("Не удалось получить снимок окна", e); }
        if (image == null) throw new IllegalStateException("Снимок доступен только при открытом GUI");
        return McpSchema.CallToolResult.builder(List.of(new McpSchema.ImageContent(null,
            Base64.getEncoder().encodeToString(image), "image/png"))).build();
    }

    private static Map<String, Object> schema(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "additionalProperties", false);
    }
    private static Map<String, Object> stringProperty(String description) { return Map.of("type", "string", "description", description); }
    private static Map<String, Object> enumStringProperty(String description) {
        return Map.of("type", "string", "description", description, "enum", List.of("samples", "controlPoints"));
    }
    private static Map<String, Object> stringEnumProperty(String description, List<String> values) {
        return Map.of("type", "string", "description", description, "enum", values);
    }
    private static Map<String, Object> numberProperty(String description) { return Map.of("type", "number", "description", description); }
    private static Map<String, Object> integerProperty(String description) { return Map.of("type", "integer", "description", description); }
    private static Map<String, Object> objectProperty(String description) { return Map.of("type", "object", "description", description, "additionalProperties", Map.of("type", "number")); }
    private static Map<String, Object> arrayProperty(String description) {
        return Map.of("type", "array", "description", description, "items", Map.of("type", "object", "properties", Map.of(
            "x", Map.of("type", "number"), "y", Map.of("type", "number")), "required", List.of("x", "y"), "additionalProperties", false));
    }
    private static Map<String, Object> numericArrayProperty(String description) {
        return Map.of("type", "array", "description", description, "items", Map.of("type", "number"));
    }
    private static Map<String, Object> numberGridProperty(String description) {
        return Map.of("type", "object", "description", description, "additionalProperties",
            Map.of("type", "array", "items", Map.of("type", "number")));
    }
    private static List<?> requireList(Object value, String name) {
        if (!(value instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException(name + " должен быть непустым массивом");
        return list;
    }
    private static double number(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new IllegalArgumentException("Ожидается конечное числовое значение");
        return n.doubleValue();
    }
    private static int integerNumber(Object value) {
        double number = number(value);
        if (number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Ожидается целое число");
        return (int) number;
    }
    private static boolean bool(Object value) {
        if (value instanceof Boolean result) return result;
        throw new IllegalArgumentException("Ожидается логическое значение true или false");
    }
    private static String str(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        return value == null ? fallback : value.toString();
    }
    private static McpSchema.CallToolResult textResult(String text, boolean error) {
        return McpSchema.CallToolResult.builder(List.of(new McpSchema.TextContent(text))).isError(error).build();
    }

    @Override public void close() {
        calculations.shutdownNow();
        if (mcpServer != null) mcpServer.close();
        if (httpServer != null) {
            try { httpServer.stop(); } catch (Exception ignored) { }
        }
    }
}
