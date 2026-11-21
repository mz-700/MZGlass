package org.tbc.glass.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Captures the entry point, include search paths, and unsaved UTF-8 source text.
 * Relative paths are relative to the process working directory. */
public record AssemblyRequest(Path sourcePath, List<Path> includePaths, Map<Path, String> sourceOverrides,
                              boolean ignoreMzfDirectives) {
    public AssemblyRequest {
        Objects.requireNonNull(sourcePath, "sourcePath");
        includePaths = List.copyOf(includePaths);
        sourceOverrides = Map.copyOf(sourceOverrides);
    }

    public AssemblyRequest(Path sourcePath, List<Path> includePaths, Map<Path,String> sourceOverrides) {
        this(sourcePath,includePaths,sourceOverrides,false);
    }
    public AssemblyRequest(Path sourcePath) { this(sourcePath, List.of(), Map.of()); }
}
