package org.tbc.glass.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.tbc.glass.AssemblyException;

/** A primary source range and additional include/macro contexts, innermost first. */
public record Diagnostic(Severity severity, String message, SourceRange range, List<SourceRange> related) {
    public enum Severity { ERROR, WARNING }

    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(range, "range");
        related = List.copyOf(related);
    }

    public static Diagnostic fromException(AssemblyException error, Path fallbackPath) {
        List<SourceRange> ranges = error.getContexts().stream().map(span -> span.toRange()).distinct().toList();
        return new Diagnostic(Severity.ERROR, error.getPlainMessage(),
                ranges.isEmpty() ? new SourceRange(fallbackPath, 0, 0) : ranges.getFirst(),
                ranges.isEmpty() ? List.of() : ranges.subList(1, ranges.size()));
    }
}
