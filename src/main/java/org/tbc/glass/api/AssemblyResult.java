package org.tbc.glass.api;

import java.util.List;

/** Immutable object bytes and diagnostics. The default format is raw BIN; callers may request MZF.
 * Failed assemblies expose no partial bytes. */
public record AssemblyResult(byte[] objectCode, List<Diagnostic> diagnostics) {
    public AssemblyResult {
        objectCode = objectCode.clone();
        diagnostics = List.copyOf(diagnostics);
    }

    @Override public byte[] objectCode() { return objectCode.clone(); }

    public boolean succeeded() {
        return diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR);
    }
}
