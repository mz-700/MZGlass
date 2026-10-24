package org.tbc.glass.api;

import java.util.ArrayList;
import java.util.List;

import org.tbc.glass.AssemblyException;
import org.tbc.glass.Assembler;
import org.tbc.glass.Source;
import org.tbc.glass.SourceBuilder;
import org.tbc.glass.SourceProvider;

/** Stateless, in-process assembly. Each invocation owns fresh mutable assembly state.
 * The strict assembler reports its first error; parser recovery is not provided here. */
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
        List<Diagnostic> diagnostics = new ArrayList<>();
        try {
            SourceProvider inputs = SourceProvider.overlay(request.sourceOverrides(), fallback);
            Source source = new SourceBuilder(request.includePaths(), inputs, diagnostics::add).parse(request.sourcePath());
            source.register();
            source.expand();
            source.resolve();
            return new AssemblyResult(Assembler.encodeObject(source, source.getBytes(), outputType), diagnostics);
        } catch (AssemblyException error) {
            diagnostics.add(Diagnostic.fromException(error, request.sourcePath()));
            return new AssemblyResult(new byte[0], diagnostics);
        }
    }
}
