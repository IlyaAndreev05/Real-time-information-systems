package ru.ilyaandreev.rtis;

import javafx.application.Application;
import ru.ilyaandreev.rtis.mcp.McpControlServer;
import ru.ilyaandreev.rtis.storage.ProjectSession;
import ru.ilyaandreev.rtis.storage.ProjectStore;

import java.util.List;
import java.nio.file.Path;

/** Non-JavaFX entry point so the same packaged jar can start headless MCP mode. */
public final class Launcher {
    private Launcher() { }

    public static void main(String[] args) throws Exception {
        List<String> arguments = List.of(args);
        String autosaveDirectory = option(arguments, "--autosave-dir");
        if (autosaveDirectory != null) System.setProperty("rtis.autosave.dir", Path.of(autosaveDirectory).toAbsolutePath().toString());
        int port = Integer.parseInt(option(arguments, "--port", "8765"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Порт должен быть от 1 до 65535");
        System.setProperty("rtis.mcp.port", Integer.toString(port));
        if (arguments.contains("--mcp-stdio")) {
            ProjectStore store = new ProjectStore();
            ProjectSession session = new ProjectSession(store, ProjectSession.defaultAutosavePath());
            McpControlServer server = new McpControlServer(session, store, () -> null);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            server.startStdio();
            Thread.currentThread().join();
            return;
        }
        if (arguments.contains("--mcp-http-only")) {
            ProjectStore store = new ProjectStore();
            ProjectSession session = new ProjectSession(store, ProjectSession.defaultAutosavePath());
            McpControlServer server = new McpControlServer(session, store, () -> null);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            server.startHttp(port);
            Thread.currentThread().join();
            return;
        }
        Application.launch(App.class, arguments.stream().filter(argument -> argument.equals("--mcp-http")).toArray(String[]::new));
    }

    private static String option(List<String> args, String key) {
        String prefix = key + "=";
        return args.stream().filter(argument -> argument.startsWith(prefix)).map(argument -> argument.substring(prefix.length()))
            .findFirst().orElse(null);
    }

    private static String option(List<String> args, String key, String fallback) {
        String value = option(args, key);
        return value == null ? fallback : value;
    }
}
