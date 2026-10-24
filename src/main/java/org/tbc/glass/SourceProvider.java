package org.tbc.glass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Supplies source and binary inputs without imposing an editor or filesystem layout. */
public interface SourceProvider {
    SourceFile readSource(Path path);

    boolean exists(Path path);

    default byte[] readBinary(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException error) {
            throw new AssemblyException("Cannot read binary file: " + path, error);
        }
    }

    /** Identity used for include-once and cycle detection, including unsaved files. */
    default Path identity(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException error) {
            return path.toAbsolutePath().normalize();
        }
    }

    static SourceProvider fileSystem() {
        return new SourceProvider() {
            @Override public SourceFile readSource(Path path) { return new SourceFile(path); }
            @Override public boolean exists(Path path) { return Files.exists(path); }
        };
    }

    /** Captures the supplied UTF-8 source overrides; other inputs are read from disk. */
    static SourceProvider overlay(Map<Path, String> sources) {
        return overlay(sources, fileSystem());
    }

    /** Overrides source text while retaining another provider for other inputs. */
    static SourceProvider overlay(Map<Path, String> sources, SourceProvider fallback) {
        Objects.requireNonNull(fallback, "fallback");
        Map<Path, String> snapshots = new HashMap<>();
        sources.forEach((path, text) -> snapshots.put(fallback.identity(path), Objects.requireNonNull(text, "source text")));
        Map<Path, String> captured = Map.copyOf(snapshots);
        return new SourceProvider() {
            @Override public SourceFile readSource(Path path) {
                String text = captured.get(identity(path));
                return text != null ? new SourceFile(path, text) : fallback.readSource(path);
            }
            @Override public boolean exists(Path path) { return captured.containsKey(identity(path)) || fallback.exists(path); }
            @Override public byte[] readBinary(Path path) { return fallback.readBinary(path); }
            @Override public Path identity(Path path) { return fallback.identity(path); }
        };
    }
}
