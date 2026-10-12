package ru.ilyaandreev.rtis.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ilyaandreev.rtis.model.LabProject;
import ru.ilyaandreev.rtis.model.Point;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectStoreTest {
    @TempDir Path temp;

    @Test void roundTripsTheVersionedProject() throws Exception {
        ProjectStore store = new ProjectStore();
        LabProject project = new LabProject();
        project.setName("Мой сигнал");
        project.setInput(List.of(new Point(0, 1), new Point(1, -1)));
        project.setNoiseSeed(321);
        Path file = temp.resolve("project.json");
        store.save(file, project);
        LabProject loaded = store.load(file);
        assertEquals(project.getSchemaVersion(), loaded.getSchemaVersion());
        assertEquals(project.getName(), loaded.getName());
        assertEquals(project.getInput(), loaded.getInput());
        assertEquals(project.getNoiseSeed(), loaded.getNoiseSeed());
    }

    @Test void damagedImportLeavesTheOpenProjectUntouched() throws Exception {
        ProjectStore store = new ProjectStore();
        Path autosave = temp.resolve("last.json"), damaged = temp.resolve("damaged.json");
        LabProject current = new LabProject(); current.setName("keep"); store.save(autosave, current);
        Files.writeString(damaged, "{ broken json");
        ProjectSession session = new ProjectSession(store, autosave);
        assertThrows(IOException.class, () -> session.replaceFrom(damaged));
        assertEquals("keep", session.snapshot().getName());
    }

    @Test void outdatedResultsAreNotPublishedAfterInputChanges() throws Exception {
        ProjectStore store = new ProjectStore();
        ProjectSession session = new ProjectSession(store, temp.resolve("last.json"));
        long oldRevision = session.revision();
        session.update(project -> { project.setName("new version"); return project; });
        var outdated = new ru.ilyaandreev.rtis.model.ExperimentResult("job", "spectrum", "fourier", oldRevision, List.of(), java.util.Map.of());
        assertFalse(session.publishResult(outdated));
    }
}
