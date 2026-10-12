package ru.ilyaandreev.rtis.storage;

import ru.ilyaandreev.rtis.model.ExperimentResult;
import ru.ilyaandreev.rtis.model.LabProject;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Serializes GUI and MCP edits through one project and marks outdated calculations. */
public final class ProjectSession {
    private final ProjectStore store;
    private final Path autosavePath;
    private final CopyOnWriteArrayList<Consumer<LabProject>> listeners = new CopyOnWriteArrayList<>();
    private LabProject project;
    private long revision;
    private ExperimentResult latestResult;

    public ProjectSession(ProjectStore store, Path autosavePath) {
        this.store = Objects.requireNonNull(store);
        this.autosavePath = Objects.requireNonNull(autosavePath);
        this.project = new LabProject();
        try {
            if (autosavePath.toFile().isFile()) this.project = store.load(autosavePath);
        } catch (IOException e) {
            System.err.println("Не удалось восстановить автосохранение: " + e.getMessage());
        }
    }

    public static Path defaultAutosavePath() {
        String appData = System.getenv("APPDATA");
        Path root = appData != null && !appData.isBlank()
            ? Path.of(appData, "RTIS Lab")
            : Path.of(System.getProperty("user.home"), ".rtis-lab");
        return root.resolve("last-project.json");
    }

    public synchronized LabProject snapshot() { return project.copy(); }
    public synchronized long revision() { return revision; }
    public synchronized ExperimentResult latestResult() { return latestResult; }

    public void update(UnaryOperator<LabProject> operation) throws IOException {
        LabProject next;
        synchronized (this) {
            next = Objects.requireNonNull(operation.apply(project.copy()));
            next.setUpdatedAt(Instant.now().toString());
            next.setResult(java.util.List.of());
            next.setMetrics(java.util.Map.of());
            store.save(autosavePath, next);
            project = next.copy();
            revision++;
            latestResult = null;
            next = project.copy();
        }
        notifyListeners(next);
    }

    /** Persists a presentation-only edit without invalidating the numeric result revision. */
    public void updatePresentation(UnaryOperator<LabProject> operation) throws IOException {
        LabProject next;
        synchronized (this) {
            next = Objects.requireNonNull(operation.apply(project.copy()));
            next.setUpdatedAt(Instant.now().toString());
            store.save(autosavePath, next);
            project = next.copy();
            next = project.copy();
        }
        notifyListeners(next);
    }

    public void replaceFrom(Path file) throws IOException {
        LabProject incoming = store.load(file); // Fully parse before changing the live session.
        LabProject snapshot;
        synchronized (this) {
            incoming.setUpdatedAt(Instant.now().toString());
            store.save(autosavePath, incoming);
            project = incoming.copy();
            revision++;
            latestResult = null;
            snapshot = project.copy();
        }
        notifyListeners(snapshot);
    }

    public void saveAs(Path file) throws IOException { store.save(file, snapshot()); }

    public synchronized boolean publishResult(ExperimentResult result) {
        if (result.inputRevision() != revision) return false;
        LabProject next = project.copy();
        next.setResult(result.points());
        next.setMetrics(result.metrics());
        next.setUpdatedAt(Instant.now().toString());
        latestResult = result;
        project = next;
        try { store.save(autosavePath, project); }
        catch (IOException e) { System.err.println("Не удалось сохранить результат: " + e.getMessage()); }
        notifyListeners(project.copy());
        return true;
    }

    public void addListener(Consumer<LabProject> listener) {
        listeners.add(listener);
        listener.accept(snapshot());
    }

    private void notifyListeners(LabProject snapshot) {
        for (Consumer<LabProject> listener : listeners) listener.accept(snapshot.copy());
    }
}
