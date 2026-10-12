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
import ru.ilyaandreev.rtis.core.AnalysisEngine;
import ru.ilyaandreev.rtis.core.SignalMath;
import ru.ilyaandreev.rtis.export.ChartRenderer;
import ru.ilyaandreev.rtis.model.ExperimentResult;
import ru.ilyaandreev.rtis.model.LabProject;
import ru.ilyaandreev.rtis.model.Point;
import ru.ilyaandreev.rtis.storage.ProjectSession;
import ru.ilyaandreev.rtis.storage.ProjectStore;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
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
        add(builder, "project_set_signal", "Задать исходный сигнал массивом координат [{x,y}, ...].", schema(Map.of("points", arrayProperty("Координаты точек сигнала"))), args -> setPoints(args));
        add(builder, "project_generate_sine", "Сформировать гармонический тестовый сигнал.", schema(Map.of(
            "amplitude", numberProperty("Амплитуда"), "frequency", numberProperty("Частота в герцах"),
            "phase", numberProperty("Начальная фаза в радианах"), "duration", numberProperty("Длительность в секундах"),
            "count", integerProperty("Число отсчётов"))), args -> generateSine(args));
        add(builder, "project_set_analysis", "Выбрать модуль, алгоритм и параметры расчёта.", schema(Map.of(
            "moduleId", stringProperty("Идентификатор модуля"), "algorithmId", stringProperty("Идентификатор алгоритма"),
            "parameters", objectProperty("Числовые параметры алгоритма"))), args -> setAnalysis(args));
        add(builder, "experiment_start", "Запустить расчёт асинхронно; возвращает jobId.", schema(Map.of()), args -> startExperiment());
        add(builder, "job_get", "Получить состояние и результат расчёта.", schema(Map.of("jobId", stringProperty("Идентификатор расчёта"))), args -> getJob(args));
        add(builder, "job_cancel", "Отменить выполняемый расчёт.", schema(Map.of("jobId", stringProperty("Идентификатор расчёта"))), args -> cancelJob(args));
        add(builder, "results_page", "Получить страницу точек текущего результата; не более 2000 точек за вызов.", schema(Map.of(
            "offset", integerProperty("Начальный индекс"), "limit", integerProperty("Число точек"))), args -> resultsPage(args));
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
        return Map.of("modules", AlgorithmCatalog.MODULES, "algorithms", AlgorithmCatalog.ALGORITHMS.values());
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
        session.update(p -> { p.setInput(points); return p; });
        return Map.of("pointCount", points.size(), "revision", session.revision());
    }

    private Object generateSine(Map<String, Object> args) throws IOException {
        double amplitude = number(args.get("amplitude"));
        double frequency = number(args.get("frequency"));
        double phase = number(args.get("phase"));
        double duration = number(args.get("duration"));
        int count = (int) number(args.get("count"));
        List<Point> points = SignalMath.sine(amplitude, frequency, phase, duration, count);
        session.update(p -> { p.setInput(points); return p; });
        return Map.of("pointCount", points.size(), "revision", session.revision());
    }

    private Object setAnalysis(Map<String, Object> args) throws IOException {
        String module = str(args, "moduleId", "");
        String algorithm = str(args, "algorithmId", "");
        AlgorithmCatalog.Algorithm definition = AlgorithmCatalog.ALGORITHMS.get(algorithm);
        if (definition == null || !definition.moduleId().equals(module)) throw new IllegalArgumentException("Алгоритм не принадлежит выбранному модулю");
        Map<String, Double> params = new HashMap<>();
        Object raw = args.get("parameters");
        if (raw instanceof Map<?, ?> map) for (var entry : map.entrySet()) params.put(String.valueOf(entry.getKey()), number(entry.getValue()));
        session.update(p -> { p.setModuleId(module); p.setAlgorithmId(algorithm); p.setParameters(params); return p; });
        return Map.of("moduleId", module, "algorithmId", algorithm, "parameters", params, "revision", session.revision());
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
        int offset = Math.max(0, (int) number(args.getOrDefault("offset", 0)));
        int limit = Math.min(2000, Math.max(1, (int) number(args.getOrDefault("limit", 500))));
        List<Point> points = p.getResult().isEmpty() ? p.getInput() : p.getResult();
        int from = Math.min(offset, points.size()), to = Math.min(points.size(), from + limit);
        return Map.of("offset", from, "limit", limit, "total", points.size(), "points", points.subList(from, to), "metrics", p.getMetrics());
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
        if (format.equals("csv")) java.nio.file.Files.writeString(path, ChartRenderer.csv(points));
        else if (format.equals("json")) mapper.writeValue(path.toFile(), points);
        else throw new IllegalArgumentException("Формат должен быть csv или json");
        return Map.of("path", path.toAbsolutePath().toString(), "series", series, "format", format, "pointCount", points.size());
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
    private static Map<String, Object> numberProperty(String description) { return Map.of("type", "number", "description", description); }
    private static Map<String, Object> integerProperty(String description) { return Map.of("type", "integer", "description", description); }
    private static Map<String, Object> objectProperty(String description) { return Map.of("type", "object", "description", description, "additionalProperties", Map.of("type", "number")); }
    private static Map<String, Object> arrayProperty(String description) {
        return Map.of("type", "array", "description", description, "items", Map.of("type", "object", "properties", Map.of(
            "x", Map.of("type", "number"), "y", Map.of("type", "number")), "required", List.of("x", "y"), "additionalProperties", false));
    }
    private static double number(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new IllegalArgumentException("Ожидается конечное числовое значение");
        return n.doubleValue();
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
