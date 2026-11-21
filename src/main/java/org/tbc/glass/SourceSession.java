package org.tbc.glass;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.tbc.glass.api.Diagnostic;

/** One assembly's inputs and include bookkeeping, shared by its nested builders. */
final class SourceSession implements SourceProvider {
    private final SourceProvider provider;
    private final Map<Path, SourceFile> sources = new HashMap<>();
    private final Map<Path, byte[]> binaries = new HashMap<>();
    private final Set<Path> loaded = new HashSet<>();
    private final Set<Path> active = new HashSet<>();
    final Consumer<Diagnostic> diagnostics;
    final boolean ignoreMzfDirectives;

    SourceSession(SourceProvider provider, Consumer<Diagnostic> diagnostics) {
        this(provider,diagnostics,false);
    }
    SourceSession(SourceProvider provider, Consumer<Diagnostic> diagnostics, boolean ignoreMzfDirectives) {
        this.provider = provider;
        this.diagnostics = diagnostics;
        this.ignoreMzfDirectives = ignoreMzfDirectives;
    }

    @Override public Path identity(Path path) { return provider.identity(path); }
    @Override public boolean exists(Path path) { return sources.containsKey(identity(path)) || provider.exists(path); }
    @Override public SourceFile readSource(Path path) { return sources.computeIfAbsent(identity(path), ignored -> provider.readSource(path)); }
    @Override public byte[] readBinary(Path path) { return binaries.computeIfAbsent(identity(path), ignored -> provider.readBinary(path)).clone(); }

    boolean hasLoaded(Path path) { return loaded.contains(identity(path)); }

    void enter(SourceFile file) {
        Path path = file.getPath();
        if (path == null) return;
        Path key = identity(path);
        if (!active.add(key)) throw new AssemblyException("Circular include: " + path);
        loaded.add(key);
        sources.putIfAbsent(key, file);
    }

    void leave(SourceFile file) {
        if (file.getPath() != null) active.remove(identity(file.getPath()));
    }
}
