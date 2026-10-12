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
        project.setInputKind("controlPoints");
        project.setNoiseSeed(321);
        project.setSpectrumLowHarmonic(3);
        project.setSpectrumHighHarmonic(14);
        project.setSelectedHarmonic(7);
        project.setLockTwelveHarmonics(true);
        project.setNotchFilterEnabled(true);
        project.setMeasurementCursorX(0.75);
        Path file = temp.resolve("project.json");
        store.save(file, project);
        LabProject loaded = store.load(file);
        assertEquals(project.getSchemaVersion(), loaded.getSchemaVersion());
        assertEquals(project.getName(), loaded.getName());
        assertEquals(project.getInput(), loaded.getInput());
        assertEquals("controlPoints", loaded.getInputKind());
        assertEquals(project.getNoiseSeed(), loaded.getNoiseSeed());
        assertEquals(project.getSpectrumLowHarmonic(), loaded.getSpectrumLowHarmonic());
        assertEquals(project.getSpectrumHighHarmonic(), loaded.getSpectrumHighHarmonic());
        assertEquals(project.getSelectedHarmonic(), loaded.getSelectedHarmonic());
        assertTrue(loaded.isLockTwelveHarmonics());
        assertTrue(loaded.isNotchFilterEnabled());
        assertEquals(0.75, loaded.getMeasurementCursorX());
    }

    @Test void versionOneProjectsMigrateWithDefaultPresentationControls() throws Exception {
        Path file = temp.resolve("v1.json");
        Files.writeString(file, "{\"schemaVersion\":1,\"name\":\"Старый проект\",\"input\":[],\"result\":[],\"parameters\":{},\"metrics\":{}}", java.nio.charset.StandardCharsets.UTF_8);
        LabProject loaded = new ProjectStore().load(file);
        assertEquals(LabProject.CURRENT_SCHEMA_VERSION, loaded.getSchemaVersion());
        assertEquals(1, loaded.getSpectrumLowHarmonic());
        assertEquals(12, loaded.getSpectrumHighHarmonic());
        assertEquals("samples", loaded.getInputKind());
    }

    @Test void versionTwoProjectsMigrateToManualPointAwareSchema() throws Exception {
        Path file = temp.resolve("v2.json");
        Files.writeString(file, "{\"schemaVersion\":2,\"name\":\"Проект v2\",\"input\":[],\"result\":[],\"parameters\":{},\"metrics\":{},\"measurementCursorX\":0.5}", java.nio.charset.StandardCharsets.UTF_8);
        LabProject loaded = new ProjectStore().load(file);
        assertEquals(LabProject.CURRENT_SCHEMA_VERSION, loaded.getSchemaVersion());
        assertEquals("samples", loaded.getInputKind());
        assertEquals(0.5, loaded.getMeasurementCursorX());
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

    @Test void presentationChangesPreserveTheCalculatedResultAndItsRevision() throws Exception {
        ProjectStore store = new ProjectStore();
        ProjectSession session = new ProjectSession(store, temp.resolve("last.json"));
        session.update(p -> { p.setInput(List.of(new Point(0, 0), new Point(1, 1))); return p; });
        long revision = session.revision();
        var result = new ru.ilyaandreev.rtis.model.ExperimentResult("result", "spectrum", "fourier", revision,
            List.of(new Point(0, 1), new Point(1, 0)), java.util.Map.of());
        assertTrue(session.publishResult(result));
        session.updatePresentation(p -> { p.setSelectedHarmonic(1); return p; });
        assertEquals(revision, session.revision());
        assertEquals(result.points(), session.snapshot().getResult());
    }
}
