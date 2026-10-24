package org.tbc.glass.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tbc.glass.AssemblyException;
import org.tbc.glass.SourceBuilder;
import org.tbc.glass.SourceFile;
import org.tbc.glass.SourceProvider;

class AssemblyServiceTest {
    @TempDir Path directory;
    private final AssemblyService service = new AssemblyService();

    private AssemblyRequest request(String source) {
        Path path = directory.resolve("main.asm");
        return new AssemblyRequest(path, List.of(), Map.of(path, source));
    }

    @Test void assemblesUnsavedEntryAndNestedIncludesWithoutWritingFiles() {
        Path main = directory.resolve("main.asm"), constants = directory.resolve("inc/constants.asm"), nested = directory.resolve("inc/nested.asm");
        AssemblyResult result = service.assemble(new AssemblyRequest(main, List.of(), Map.of(
                main, " include \"inc/constants.asm\"\n ld a,answer",
                constants, " include \"nested.asm\"\nanswer: equ value",
                nested, "value: equ 42")));
        assertTrue(result.succeeded(), result.diagnostics().toString());
        assertArrayEquals(new byte[] {0x3e, 42}, result.objectCode());
        assertFalse(Files.exists(main));
        assertFalse(Files.exists(constants));
    }

    @Test void unsavedTextOverridesDiskAndUsesDiskForOtherIncludes() throws IOException {
        Path main = directory.resolve("main.asm"), constant = directory.resolve("constant.asm"), disk = directory.resolve("disk.asm");
        Files.writeString(main, " invalid_instruction");
        Files.writeString(constant, "value: equ 1");
        Files.writeString(disk, " db 7");
        AssemblyResult result = service.assemble(new AssemblyRequest(main, List.of(), Map.of(
                main, " include \"constant.asm\"\n include \"disk.asm\"\n db value", constant, "value: equ 9")));
        assertTrue(result.succeeded(), result.diagnostics().toString());
        assertArrayEquals(new byte[] {7, 9}, result.objectCode());
        assertEquals("value: equ 1", Files.readString(constant));
    }

    @Test void includeSearchUsesTheSourceDirectoryBeforeConfiguredPaths() {
        Path main = directory.resolve("project/main.asm"), local = directory.resolve("project/defs.asm"), library = directory.resolve("library");
        AssemblyResult result = service.assemble(new AssemblyRequest(main, List.of(library), Map.of(
                main, " include \"defs.asm\"\n db value", local, "value: equ 5", library.resolve("defs.asm"), "value: equ 8")));
        assertArrayEquals(new byte[] {5}, result.objectCode());
        result = service.assemble(new AssemblyRequest(main, List.of(library), Map.of(
                main, " include \"defs.asm\"\n db value", library.resolve("defs.asm"), "value: equ 8")));
        assertArrayEquals(new byte[] {8}, result.objectCode());
    }

    @Test void includeOnceIsSharedAcrossNestedBuildersButNotAssemblies() {
        Path main = directory.resolve("main.asm"), common = directory.resolve("common.asm"), other = directory.resolve("other.asm");
        AssemblyRequest request = new AssemblyRequest(main, List.of(), Map.of(
                main, " include once \"common.asm\"\n include \"other.asm\"\n db 3",
                common, " db 1", other, " include once \"common.asm\"\n db 2"));
        for (int i = 0; i < 3; i++) {
            AssemblyResult result = service.assemble(request);
            assertTrue(result.succeeded(), result.diagnostics().toString());
            assertArrayEquals(new byte[] {1, 2, 3}, result.objectCode());
        }
    }

    @Test void includeOnceRecognizesNormalizedPathsAndAnInMemoryRoot() {
        Path main = directory.resolve("main.asm"), common = directory.resolve("common.asm");
        AssemblyResult result = service.assemble(new AssemblyRequest(main, List.of(), Map.of(
                main, " include once \"main.asm\"\n include once \"common.asm\"\n include once \"unused/../common.asm\"",
                common, " nop")));
        assertTrue(result.succeeded(), result.diagnostics().toString());
        assertArrayEquals(new byte[] {0}, result.objectCode());
    }

    @Test void includeOnceDoesNotLeakBetweenDiskAssemblies() throws IOException {
        Path main = directory.resolve("main.asm"), included = directory.resolve("once.asm");
        Files.writeString(main, " include once \"once.asm\"\n db value\n");
        Files.writeString(included, "value: equ 23\n");
        for (int i = 0; i < 3; i++) {
            AssemblyResult result = service.assemble(new AssemblyRequest(main));
            assertTrue(result.succeeded(), result.diagnostics().toString());
            assertArrayEquals(new byte[] {23}, result.objectCode());
        }
    }

    @Test void anOrdinaryIncludeCanBeRepeated() {
        Path main = directory.resolve("main.asm"), common = directory.resolve("common.asm");
        AssemblyResult result = service.assemble(new AssemblyRequest(main, List.of(), Map.of(
                main, " include \"common.asm\"\n include \"common.asm\"", common, " nop")));
        assertArrayEquals(new byte[] {0, 0}, result.objectCode());
    }

    @Test void circularIncludesProduceADiagnosticInsteadOfStackOverflow() {
        Path main = directory.resolve("main.asm"), other = directory.resolve("other.asm");
        AssemblyResult result = service.assemble(new AssemblyRequest(main, List.of(), Map.of(
                main, " include \"other.asm\"", other, " include \"main.asm\"")));
        assertFalse(result.succeeded());
        Diagnostic error = result.diagnostics().getFirst();
        assertTrue(error.message().startsWith("Circular include:"));
        assertEquals(other, error.range().path());
        assertTrue(error.related().stream().anyMatch(range -> range.path().equals(main)));
    }

    @Test void snapshotsAndBinaryInputsAreReadOncePerAssembly() {
        Path main = directory.resolve("main.asm"), common = directory.resolve("common.asm"), binary = directory.resolve("data.bin");
        AtomicInteger sourceReads = new AtomicInteger(), binaryReads = new AtomicInteger();
        SourceProvider provider = new SourceProvider() {
            @Override public SourceFile readSource(Path path) {
                assertEquals(common, path);
                return new SourceFile(path, " db " + sourceReads.incrementAndGet());
            }
            @Override public boolean exists(Path path) { return path.equals(common) || path.equals(binary); }
            @Override public byte[] readBinary(Path path) { assertEquals(binary, path); binaryReads.incrementAndGet(); return new byte[] {7, 8}; }
        };
        AssemblyRequest request = new AssemblyRequest(main, List.of(), Map.of(main,
                " include \"common.asm\"\n include \"common.asm\"\n incbin \"data.bin\"\n incbin \"data.bin\",1,1"));
        assertArrayEquals(new byte[] {1, 1, 7, 8, 8}, service.assemble(request, provider).objectCode());
        assertEquals(1, sourceReads.get()); assertEquals(1, binaryReads.get());
        assertArrayEquals(new byte[] {2, 2, 7, 8, 8}, service.assemble(request, provider).objectCode());
        assertEquals(2, sourceReads.get()); assertEquals(2, binaryReads.get());
    }

    @Test void defaultProviderReadsBinaryFiles() throws IOException {
        Files.write(directory.resolve("data.bin"), new byte[] {1, 2, 3});
        AssemblyResult result = service.assemble(request(" incbin \"data.bin\",1,2"));
        assertTrue(result.succeeded(), result.diagnostics().toString());
        assertArrayEquals(new byte[] {2, 3}, result.objectCode());
    }

    @Test void concurrentCallsHaveIndependentIncludeOnceState() throws Exception {
        Path main = directory.resolve("main.asm"), include = directory.resolve("value.asm");
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<AssemblyResult>> jobs = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                int value = i;
                jobs.add(() -> service.assemble(new AssemblyRequest(main, List.of(), Map.of(
                        main, " include once \"value.asm\"\n db value", include, "value: equ " + value))));
            }
            var results = executor.invokeAll(jobs);
            for (int i = 0; i < results.size(); i++) {
                AssemblyResult result = results.get(i).get();
                assertTrue(result.succeeded(), result.diagnostics().toString());
                assertArrayEquals(new byte[] {(byte)i}, result.objectCode());
            }
        }
    }

    @Test void syntaxErrorRangeUsesLfNormalizedUtf16Offsets() {
        String text = "; café 🚀\r\n ld a,]";
        AssemblyResult result = service.assemble(request(text));
        assertFalse(result.succeeded());
        Diagnostic diagnostic = result.diagnostics().getFirst();
        int error = text.replace("\r\n", "\n").indexOf(']');
        assertEquals(new SourceRange(directory.resolve("main.asm"), error, error + 1), diagnostic.range());
        assertEquals("Syntax error.", diagnostic.message());
    }

    @Test void aMultilineExpressionDiagnosticPointsToItsLastLineColumn() {
        String text = " ld a,(\n 1+]";
        AssemblyResult result = service.assemble(request(text));
        assertFalse(result.succeeded());
        assertEquals(text.indexOf(']'), result.diagnostics().getFirst().range().from());
    }

    @Test void includeErrorKeepsItsOriginalFileAndParentContext() {
        Path main = directory.resolve("main.asm"), included = directory.resolve("bad.asm");
        AssemblyResult result = service.assemble(new AssemblyRequest(main, List.of(), Map.of(
                main, " include \"bad.asm\"", included, " ld a,]")));
        Diagnostic error = result.diagnostics().getFirst();
        assertEquals(included, error.range().path());
        assertEquals(6, error.range().from());
        assertTrue(error.related().stream().anyMatch(range -> range.path().equals(main)));
    }

    @Test void missingEntryAndIncludesReturnStructuredErrors() {
        Path missing = directory.resolve("missing.asm");
        AssemblyResult result = service.assemble(new AssemblyRequest(missing));
        assertFalse(result.succeeded());
        assertEquals(new SourceRange(missing, 0, 0), result.diagnostics().getFirst().range());
        result = service.assemble(request(" include \"absent.asm\""));
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().getFirst().message().startsWith("Include file not found:"));
        assertEquals(directory.resolve("main.asm"), result.diagnostics().getFirst().range().path());
    }

    @Test void warningsAreStructuredAndDoNotFailAssembly() {
        AssemblyResult result = service.assemble(request(" warning \"check layout\"\n nop"));
        assertTrue(result.succeeded());
        assertArrayEquals(new byte[] {0}, result.objectCode());
        assertEquals(1, result.diagnostics().size());
        Diagnostic warning = result.diagnostics().getFirst();
        assertEquals(Diagnostic.Severity.WARNING, warning.severity());
        assertEquals("check layout", warning.message());
        assertEquals(directory.resolve("main.asm"), warning.range().path());
    }

    @Test void macroCopiesRetainTheDiagnosticConsumer() {
        AssemblyResult result = service.assemble(request("notify: macro\n warning \"macro warning\"\n endm\n notify"));
        assertTrue(result.succeeded(), result.diagnostics().toString());
        assertEquals(1, result.diagnostics().size());
        assertEquals("macro warning", result.diagnostics().getFirst().message());
    }

    @Test void failureKeepsEarlierWarningsButDoesNotReturnPartialObjectCode() {
        AssemblyResult result = service.assemble(request(" nop\n warning \"before failure\"\n error \"stop\""));
        assertFalse(result.succeeded());
        assertArrayEquals(new byte[0], result.objectCode());
        assertEquals(List.of(Diagnostic.Severity.WARNING, Diagnostic.Severity.ERROR), result.diagnostics().stream().map(Diagnostic::severity).toList());
        assertEquals("stop", result.diagnostics().getLast().message());
    }

    @Test void resultBytesAndCollectionsAreDefensiveCopies() {
        Map<Path, String> sources = new HashMap<>(); Path path = directory.resolve("main.asm"); sources.put(path, " db 3");
        List<Path> includes = new ArrayList<>();
        AssemblyRequest request = new AssemblyRequest(path, includes, sources);
        sources.put(path, " invalid"); includes.add(directory);
        AssemblyResult result = service.assemble(request);
        assertTrue(result.succeeded()); assertTrue(request.includePaths().isEmpty());
        byte[] bytes = result.objectCode(); bytes[0] = 99;
        assertArrayEquals(new byte[] {3}, result.objectCode());
        assertThrows(UnsupportedOperationException.class, () -> result.diagnostics().clear());
        assertThrows(UnsupportedOperationException.class, () -> request.sourceOverrides().clear());
    }

    @Test void providerOverlayCapturesItsSourceMap() {
        Path path = directory.resolve("main.asm"); Map<Path, String> sources = new HashMap<>(); sources.put(path, " nop");
        SourceProvider provider = SourceProvider.overlay(sources); sources.put(path, " invalid");
        assertEquals(" nop", provider.readSource(path).getText());
    }

    @Test void directBuilderRetainsStrictExceptionBehavior() {
        assertThrows(AssemblyException.class, () -> new SourceBuilder(List.of()).parse(new SourceFile(" ld a,]")));
    }

    @Test void binAndMzfApiOutputMatchTheCommandLineEncoder() throws IOException {
        Path main = directory.resolve("main.asm"), output = directory.resolve("reference.bin");
        String source = " mzf_title the game!\n mzf_load $1200\n mzf_start $1234\n mzf_comments comments\n nop\n rst $38\n";
        Files.writeString(main, source);
        for (var type : org.tbc.glass.Assembler.OutputType.values()) {
            new org.tbc.glass.Assembler(main, List.of()).writeObject(output, type);
            var result = service.assemble(new AssemblyRequest(main), SourceProvider.fileSystem(), type);
            assertTrue(result.succeeded(), result.diagnostics().toString());
            assertArrayEquals(Files.readAllBytes(output), result.objectCode());
        }
        var mzf = service.assemble(request(source), SourceProvider.fileSystem(), org.tbc.glass.Assembler.OutputType.MZF).objectCode();
        assertEquals(130, mzf.length);
        assertEquals(130, (mzf[18] & 255) | (mzf[19] & 255) << 8);
        assertEquals(0x1200, (mzf[20] & 255) | (mzf[21] & 255) << 8);
        assertEquals(0x1234, (mzf[22] & 255) | (mzf[23] & 255) << 8);
    }

    @Test void mzfUsesUnsavedIncludesAndConfiguredSearchPathsWithoutWritingSources() {
        Path main = directory.resolve("project/main.asm"), library = directory.resolve("library");
        var request = new AssemblyRequest(main, List.of(library), Map.of(
                main, " mzf_load $1200\n include \"values.inc\"\n ld a,VALUE\n",
                library.resolve("values.inc"), "VALUE: equ 42\n"));
        var result = service.assemble(request, SourceProvider.fileSystem(), org.tbc.glass.Assembler.OutputType.MZF);
        assertTrue(result.succeeded(), result.diagnostics().toString());
        assertEquals(130, result.objectCode().length);
        assertEquals(0x3e, result.objectCode()[128] & 255); assertEquals(42, result.objectCode()[129] & 255);
        assertFalse(Files.exists(main)); assertFalse(Files.exists(library));
    }

    @Test void mzfMetadataAndSizeErrorsReturnDiagnosticsWithoutPartialBytes() {
        for (String source : List.of(" nop\n", " mzf_load $1200\n ds 65408\n")) {
            var result = service.assemble(request(source), SourceProvider.fileSystem(), org.tbc.glass.Assembler.OutputType.MZF);
            assertFalse(result.succeeded()); assertEquals(0, result.objectCode().length);
            assertTrue(result.diagnostics().getFirst().message().startsWith("MZF"));
            assertEquals(directory.resolve("main.asm"), result.diagnostics().getFirst().range().path());
        }
        assertTrue(service.assemble(request(" nop\n")).succeeded(), "Default BIN output must not require MZF metadata");
    }
}
