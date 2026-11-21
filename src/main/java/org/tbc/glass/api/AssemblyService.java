package org.tbc.glass.api;

import java.util.ArrayList;
import java.util.List;

import org.tbc.glass.AssemblyException;
import org.tbc.glass.Assembler;
import org.tbc.glass.Source;
import org.tbc.glass.SourceBuilder;
import org.tbc.glass.SourceProvider;

/** Stateless, in-process assembly. Each invocation owns fresh mutable assembly state. */
public final class AssemblyService {
    public AssemblyResult assemble(AssemblyRequest request) {
        return assemble(request, SourceProvider.fileSystem());
    }

    /** The fallback provider must be safe for the caller's chosen concurrency model. */
    public AssemblyResult assemble(AssemblyRequest request, SourceProvider fallback) {
        return assemble(request, fallback, Assembler.OutputType.BIN);
    }

    /** Builds BIN or MZF bytes in memory, using the same encoder as the command-line assembler.
     * MZF metadata/size failures are returned as diagnostics, with no partial output. */
    public AssemblyResult assemble(AssemblyRequest request, SourceProvider fallback, Assembler.OutputType outputType) {
        return assemble(request, fallback, outputType, 0);
    }

    /** Selects a byte for ORG gaps, independently of source data and DS fill values. */
    public AssemblyResult assemble(AssemblyRequest request, SourceProvider fallback,
                                   Assembler.OutputType outputType, int originFill) {
        if (originFill < 0 || originFill > 255)
            throw new IllegalArgumentException("ORG fill must be a byte (0..255).");
        return assemble(request, fallback, outputType, originFill, false);
    }

    /** Analyze an editing snapshot with recovery for parser errors and missing includes.
     * Recovered snapshots get independent operand checks.
     * Clean snapshots use the regular assembler checks and return raw BIN bytes. */
    public AssemblyResult analyze(AssemblyRequest request, SourceProvider fallback) {
        return assemble(request, fallback, Assembler.OutputType.BIN, 0, true);
    }

    private AssemblyResult assemble(AssemblyRequest request, SourceProvider fallback,
                                    Assembler.OutputType outputType, int originFill, boolean recover) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        try {
            SourceProvider inputs = SourceProvider.overlay(request.sourceOverrides(), fallback);
            SourceBuilder builder = recover
                    ? SourceBuilder.forDiagnostics(request.includePaths(), inputs, diagnostics::add, request.ignoreMzfDirectives(),
                            error -> diagnostics.add(Diagnostic.fromException(error, request.sourcePath())))
                    : new SourceBuilder(request.includePaths(), inputs, diagnostics::add, request.ignoreMzfDirectives());
            Source source = builder.parse(request.sourcePath());
            if (recover && diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR)) {
                builder.validateRecoveredOperands();
                return new AssemblyResult(new byte[0], diagnostics);
            }
            source.register();
            source.expand();
            source.resolve();
            var object = source.getObjectCode(originFill);
            return new AssemblyResult(Assembler.encodeObject(source, object.bytes(), outputType), diagnostics,object.emissions());
        } catch (AssemblyException error) {
            diagnostics.add(Diagnostic.fromException(error, request.sourcePath()));
            return new AssemblyResult(new byte[0], diagnostics);
        }
    }
}
