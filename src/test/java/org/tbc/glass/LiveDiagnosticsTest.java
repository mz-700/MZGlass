package org.tbc.glass;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tbc.glass.api.AssemblyRequest;
import org.tbc.glass.api.AssemblyService;
import org.tbc.glass.api.Diagnostic;

import static org.junit.jupiter.api.Assertions.*;

class LiveDiagnosticsTest {
    @TempDir Path directory;
    private final AssemblyService service = new AssemblyService();

    @Test void reportsOperandErrorsAboveAndBelowBrokenSyntaxWhileStrictAssemblyReportsOnlySyntax() {
        Path main = directory.resolve("main.asm");
        String text = """
                 LD A,BC
                WAIT_CONNECT: DB 'WAITING',$0D
                 PUSH A
                 """;
        var request = new AssemblyRequest(main, List.of(), Map.of(main, text));
        var live = service.analyze(request, SourceProvider.fileSystem());
        assertFalse(live.succeeded());
        assertEquals(0, live.objectCode().length);
        var errors = live.diagnostics().stream()
                .sorted(java.util.Comparator.comparingInt(diagnostic -> diagnostic.range().from())).toList();
        assertEquals(List.of("Invalid arguments.", "Syntax error.", "Invalid arguments."),
                errors.stream().map(Diagnostic::message).toList());
        assertEquals(0, errors.getFirst().range().from());
        assertEquals(text.lastIndexOf(" PUSH"), errors.getLast().range().from());
        assertEquals(List.of(errors.get(1)), service.assemble(request).diagnostics());
    }

    @Test void recoversInIncludesAndPreservesIncludeContextsWithoutReportingDependentErrors() {
        Path main = directory.resolve("main.asm"), include = directory.resolve("defs.inc");
        var request = new AssemblyRequest(main, List.of(), Map.of(
                main, """
                         LD HL,DATA
                         INCLUDE "defs.inc"
                         IF 0
                         PUSH A
                         ENDIF
                         LD A,BC
                        """,
                include, """
                        DATA: DB 'invalid'
                        OTHER: DB 'also invalid'
                         PUSH A
                         """));
        var result = service.analyze(request, SourceProvider.fileSystem());
        assertEquals(4, result.diagnostics().size(), result.diagnostics().toString());
        var included = result.diagnostics().stream().filter(diagnostic -> diagnostic.range().path().equals(include)).toList();
        assertEquals(List.of("Syntax error.", "Syntax error.", "Invalid arguments."),
                included.stream().map(Diagnostic::message).toList());
        assertTrue(included.stream().allMatch(diagnostic -> diagnostic.related().getFirst().path().equals(main)));
        assertEquals("Invalid arguments.", result.diagnostics().getLast().message());
        assertEquals(main, result.diagnostics().getLast().range().path());
    }

    @Test void reportsMissingIncludesAndKeepsCheckingMainAndLaterIncludesInLiveAnalysis() {
        Path main = directory.resolve("main.asm"), library = directory.resolve("library.inc");
        String text = " include \"missing.inc\"\n include \"library.inc\"\n ld a,bc\n ld hl,MISSING_SYMBOL\n";
        var request = new AssemblyRequest(main, List.of(), Map.of(
                main, text,
                library, " include once \"nested-missing.inc\"\nDATA: db 'bad'\n push a\n"));

        var live = service.analyze(request, SourceProvider.fileSystem());
        assertFalse(live.succeeded());
        assertEquals(0, live.objectCode().length);
        var mainErrors = live.diagnostics().stream().filter(error -> error.range().path().equals(main)).toList();
        assertEquals(List.of("Include file not found: missing.inc", "Invalid arguments."),
                mainErrors.stream().map(Diagnostic::message).toList());
        assertEquals(0, mainErrors.getFirst().range().from());
        assertEquals(text.indexOf(" ld a,bc"), mainErrors.getLast().range().from());
        var libraryErrors = live.diagnostics().stream().filter(error -> error.range().path().equals(library)).toList();
        assertEquals(List.of("Include file not found: nested-missing.inc", "Syntax error.", "Invalid arguments."),
                libraryErrors.stream().map(Diagnostic::message).toList());
        assertTrue(libraryErrors.stream().allMatch(error -> error.related().size() == 1 && error.related().getFirst().path().equals(main)));

        var strict = service.assemble(request);
        assertFalse(strict.succeeded());
        assertEquals(List.of(mainErrors.getFirst()), strict.diagnostics());
    }

    @Test void continuesOperandChecksAfterADuplicateLabelInAnInclude() {
        Path main = directory.resolve("main.asm"), driver = directory.resolve("driver.inc");
        String mainText = " include \"driver.inc\"\nSTART:\n\n LD A,BC\n DB 'xxx'\n DB 'yyy'\n";
        String driverText = ".wait: nop\n.wait: nop\nPAIR: equ bc\n ld a,PAIR\n";
        var request = new AssemblyRequest(main, List.of(), Map.of(main, mainText, driver, driverText));

        var live = service.analyze(request, SourceProvider.fileSystem());
        assertFalse(live.succeeded());
        var mainErrors = live.diagnostics().stream().filter(error -> error.range().path().equals(main)).toList();
        assertEquals(List.of("Syntax error.", "Syntax error.", "Invalid arguments."),
                mainErrors.stream().map(Diagnostic::message).toList());
        var operandError = mainErrors.getLast().range();
        assertTrue(operandError.from() <= mainText.indexOf("LD A,BC") && operandError.to() >= mainText.indexOf("LD A,BC") + "LD A,BC".length());
        var driverErrors = live.diagnostics().stream().filter(error -> error.range().path().equals(driver)).toList();
        assertEquals(List.of("Can not redefine symbol: .wait", "Invalid arguments."),
                driverErrors.stream().map(Diagnostic::message).toList());
        assertTrue(driverErrors.stream().allMatch(error -> error.related().getFirst().path().equals(main)));

        var strict = service.assemble(request);
        assertEquals(List.of(mainErrors.getFirst()), strict.diagnostics());
        String fixedStrings = mainText.replace('\'', '"');
        var strictWithValidSyntax = service.assemble(new AssemblyRequest(main, List.of(), Map.of(main, fixedStrings, driver, driverText)));
        assertEquals(List.of(driverErrors.getFirst()), strictWithValidSyntax.diagnostics());
    }

    @Test void cleanLiveAnalysisRetainsStrictAssemblyChecksAndBytes() {
        Path main = directory.resolve("main.asm");
        var valid = new AssemblyRequest(main, List.of(), Map.of(main, " LD A,B\nWAIT_CONNECT: DB \"WAITING\",$0D\n"));
        assertTrue(service.analyze(valid, SourceProvider.fileSystem()).succeeded());
        assertArrayEquals(service.assemble(valid).objectCode(), service.analyze(valid, SourceProvider.fileSystem()).objectCode());

        var invalid = new AssemblyRequest(main, List.of(), Map.of(main, " JR distant\n DS 256\ndistant: NOP"));
        assertFalse(service.analyze(invalid, SourceProvider.fileSystem()).succeeded());
        assertEquals(service.assemble(invalid).diagnostics(), service.analyze(invalid, SourceProvider.fileSystem()).diagnostics());
    }
}
