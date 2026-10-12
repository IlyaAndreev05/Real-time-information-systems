package ru.ilyaandreev.rtis.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import ru.ilyaandreev.rtis.model.LabProject;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class ProjectStore {
    private final ObjectMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    public void save(Path target, LabProject project) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path directory = absolute.getParent();
        if (directory != null) Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, absolute.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, mapper.writeValueAsBytes(project)); // Jackson JSON bytes are UTF-8 by specification.
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public LabProject load(Path source) throws IOException {
        LabProject project = mapper.readValue(Files.readAllBytes(source), LabProject.class);
        if (project.getSchemaVersion() == 1 || project.getSchemaVersion() == 2) {
            // v2 added presentation controls; v3 distinguishes sampled series from manual knots.
            project.setInputKind("samples");
            project.setSchemaVersion(LabProject.CURRENT_SCHEMA_VERSION);
        }
        if (project.getSchemaVersion() != LabProject.CURRENT_SCHEMA_VERSION)
            throw new IOException("Неподдерживаемая версия проекта: " + project.getSchemaVersion());
        if (project.getInput() == null || project.getParameters() == null || project.getMetrics() == null)
            throw new IOException("Файл проекта не содержит обязательные поля");
        return project;
    }
}
