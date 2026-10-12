package ru.ilyaandreev.rtis;

import javafx.application.Application;
import ru.ilyaandreev.rtis.mcp.McpControlServer;
import ru.ilyaandreev.rtis.storage.ProjectSession;
import ru.ilyaandreev.rtis.storage.ProjectStore;

import java.util.List;

/** Non-JavaFX entry point so the same packaged jar can start headless MCP mode. */
public final class Launcher {
    private Launcher() { }

    public static void main(String[] args) throws Exception {
        if (List.of(args).contains("--mcp-stdio")) {
            ProjectStore store = new ProjectStore();
            ProjectSession session = new ProjectSession(store, ProjectSession.defaultAutosavePath());
            McpControlServer server = new McpControlServer(session, store, () -> null);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            server.startStdio();
            Thread.currentThread().join();
            return;
        }
        if (List.of(args).contains("--mcp-http-only")) {
            ProjectStore store = new ProjectStore();
            ProjectSession session = new ProjectSession(store, ProjectSession.defaultAutosavePath());
            McpControlServer server = new McpControlServer(session, store, () -> null);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            server.startHttp(8765);
            Thread.currentThread().join();
            return;
        }
        Application.launch(App.class, args);
    }
}
