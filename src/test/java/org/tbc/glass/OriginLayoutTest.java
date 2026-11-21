package org.tbc.glass;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tbc.glass.api.AssemblyRequest;
import org.tbc.glass.api.AssemblyResult;
import org.tbc.glass.api.AssemblyService;
import org.tbc.glass.api.Diagnostic;

class OriginLayoutTest extends TestBase {
    @TempDir Path directory;
    private final AssemblyService service = new AssemblyService();

    private AssemblyRequest request(String text) {
        Path main = directory.resolve("main.asm");
        return new AssemblyRequest(main, List.of(), Map.of(main, text));
    }

    private byte[] assemble(String text) {
        AssemblyResult result = service.assemble(request(text));
        assertTrue(result.succeeded(), result.diagnostics().toString());
        return result.objectCode();
    }

    @Test void initialOriginDoesNotPadFromZeroEvenAfterMetadata() {
        assertArrayEquals(b(0xAA), assemble("value: equ $1200\n mzf_load value\n org value\n db $AA"));
        assertArrayEquals(b(0xAA), assemble(" org $10000\n db $AA"));
        assertArrayEquals(b(0xAA), assemble(" org -10\n db $AA"));
        assertArrayEquals(b(), assemble(" org $1200"));
    }

    @Test void forwardOriginsIncludeZeroPaddingAndPreserveRelativeAddresses() {
        assertArrayEquals(b(0xAA, 0, 0, 0, 0xBB),
                assemble(" org $1200\n db $AA\n org $1204\n db $BB"));
        assertArrayEquals(b(0x18, 4, 0, 0, 0, 0, 0x55),
                assemble(" org $1200\n jr target\n org $1206\ntarget: db $55"));
    }

    @Test void consecutiveAndTerminalOriginsRetainTheirGaps() {
        assertArrayEquals(b(0, 0, 0, 0, 0xAA),
                assemble(" org $1200\n org $1204\n db $AA"));
        assertArrayEquals(b(0xAA, 0, 0, 0), assemble(" org $1200\n db $AA\n org $1204"));
        assertArrayEquals(b(0xAA, 0xBB), assemble(" org $1200\n db $AA\n org $1201\n db $BB"));
    }

    @Test void outputWithoutAnInitialOriginStartsAtZero() {
        assertArrayEquals(b(1, 0, 0, 0, 2), assemble(" db 1\n org 4\n db 2"));
    }

    @Test void originExpressionsCanUseSelfAndForwardDefinedConstants() {
        assertArrayEquals(b(1, 0, 0, 0, 2),
                assemble(" org base\n db 1\n org $+3\n db 2\nbase: equ $1200"));
    }

    @Test void includesAndMacrosShareTheSurroundingPhysicalOutput() {
        Path main = directory.resolve("main.asm"), included = directory.resolve("data.inc");
        var result = service.assemble(new AssemblyRequest(main, List.of(), Map.of(
                main, " org $1200\n db 1\n include \"data.inc\"\n org $1206\n db 3",
                included, " org $1204\n db 2")));
        assertTrue(result.succeeded(), result.diagnostics().toString());
        assertArrayEquals(b(1, 0, 0, 0, 2, 0, 3), result.objectCode());
        assertArrayEquals(b(1, 0, 0, 0, 2), assemble(
                "emit: macro\n org $1204\n db 2\n endm\n org $1200\n db 1\n emit"));
    }

    @Test void conditionalOriginsShareTheOutputBaseAndCursor() {
        assertArrayEquals(b(1, 0, 0, 0, 2, 0, 3), assemble(
                " if 1\n org $1200\n db 1\n endif\n if 0\n org 0\n else\n"
                + " if 1\n org $1204\n db 2\n endif\n endif\n org $1206\n db 3"));
    }

    @Test void phaseHasNoGapAndPreservesExistingLabelRestoration() {
        assertArrayEquals(b(0xAA, 0xBB, 0xCC), assemble(
                " org $1200\n db $AA\n phase $3000\n db $BB\n dephase\n db $CC"));
        assertArrayEquals(b(0xAA, 0x21, 0, 0x30, 0x01, 1, 0x12), assemble(
                " org $1200\n db $AA\n phase $3000\nlabel: ld hl,label\n dephase\n ld bc,$"));
    }

    @Test void originAfterPhaseCountsThePhysicallyEmittedBytes() {
        assertArrayEquals(b(0xAA, 0x21, 0, 0x30, 0, 0, 0xBB), assemble(
                " org $1200\n db $AA\n phase $3000\n ld hl,$\n dephase\n org $1206\n db $BB"));
    }

    @Test void originInsidePhaseRemainsALogicalChangeOnly() {
        assertArrayEquals(b(0xAA, 0x21, 0, 0x31, 0, 0, 0xBB), assemble(
                " org $1200\n db $AA\n phase $3000\n org $3100\n ld hl,$\n"
                + " dephase\n org $1206\n db $BB"));
    }

    @Test void selectedConditionalInsidePhaseDoesNotInventAnOriginGap() {
        assertArrayEquals(b(0xAA, 0xBB, 0, 0, 0xCC), assemble(
                " org $1200\n db $AA\n phase $3000\n if 1\n org $3100\n db $BB\n"
                + " endif\n dephase\n org $1204\n db $CC"));
    }

    @Test void sectionOriginsAreAnchoredAndMultipleBodiesShareACursor() {
        assertArrayEquals(b(0xAA, 0, 0, 0, 0xBB, 0x86, 0x86, 0x86), assemble(
                " org $1200\nROM: ds 8,$86\n section ROM\n db $AA\n ends\n"
                + " section ROM\n org $1204\n db $BB\n ends"));
        assertArrayEquals(b(0, 0, 0, 0xBB, 0x86), assemble(
                " org $1200\nROM: ds 5,$86\n section ROM\n org $1203\n db $BB\n ends"));
    }

    @Test void sectionCapacityIncludesOriginPadding() {
        var result = service.assemble(request(
                " org $1200\nROM: ds 4\n section ROM\n db $AA\n org $1204\n db $BB\n ends"));
        assertFalse(result.succeeded());
        assertEquals(0, result.objectCode().length);
        assertTrue(result.diagnostics().getLast().message().startsWith("Section size exceeds space"));
    }

    @Test void customFillPropagatesThroughConditionalsAndSectionsWithoutChangingDsFill() {
        var result = service.assemble(request(
                " org $1200\n db $AA\n org $1202\n if 1\n org $1204\n db $BB\n endif\n"
                + "ROM: ds 8,$86\n section ROM\n org $1207\n db $CC\n ends"),
                SourceProvider.fileSystem(), Assembler.OutputType.BIN, 255);
        assertTrue(result.succeeded(), result.diagnostics().toString());
        assertArrayEquals(b(0xAA, 255, 255, 255, 0xBB, 255, 255, 0xCC,
                0x86, 0x86, 0x86, 0x86, 0x86), result.objectCode());
    }

    @Test void incbinLengthParticipatesInOriginPlacement() throws Exception {
        Files.write(directory.resolve("data.bin"), b(7, 8));
        assertArrayEquals(b(7, 8, 0, 0, 9), assemble(
                " org $1200\n incbin \"data.bin\"\n org $1204\n db 9"));
    }

    @Test void backwardOriginsReturnSourceDiagnosticsWithoutPartialBytes() {
        Path main = directory.resolve("main.asm"), bad = directory.resolve("bad.inc");
        var result = service.assemble(new AssemblyRequest(main, List.of(), Map.of(
                main, " org $1200\n db 1,2\n include \"bad.inc\"",
                bad, " org $1201\n db 3")));
        assertFalse(result.succeeded());
        assertEquals(0, result.objectCode().length);
        Diagnostic error = result.diagnostics().getLast();
        assertEquals(bad, error.range().path());
        assertEquals(0, error.range().from());
        assertTrue(error.message().startsWith("ORG overlaps earlier output"));
    }

    @Test void backwardOriginAfterPhaseUsesPhysicalRatherThanRestoredLogicalEnd() {
        var result = service.assemble(request(
                " org $1200\n phase $3000\n db 1,2,3\n dephase\n org $1201\n db 4"));
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().getLast().message().startsWith("ORG overlaps earlier output"));
    }

    @Test void anUnrepresentableGapIsADiagnosticRatherThanIntegerWraparound() {
        var result = service.assemble(request(" org -2147483647\n db 1\n org 2147483647\n db 2"));
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().getLast().message().startsWith("ORG gap exceeds"));
    }

    @Test void rawGenerationIsRepeatableAndFillDoesNotLeakBetweenCalls() throws Exception {
        Source source = new SourceBuilder(List.of()).parse(new SourceFile(
                " org $1200\n db 1\n org $1204\n db 2"));
        source.register(); source.expand(); source.resolve();
        assertArrayEquals(b(1, 0, 0, 0, 2), source.getBytes());
        assertArrayEquals(b(1, 255, 255, 255, 2), source.getBytes(255));
        assertArrayEquals(b(1, 0, 0, 0, 2), source.getBytes());
        Source second = new SourceBuilder(List.of()).parse(new SourceFile(
                " org $1200\n db 1\n org $1204\n db 2"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        second.assemble(output, 255);
        assertArrayEquals(source.getBytes(255), output.toByteArray());
        assertThrows(IllegalArgumentException.class, () -> source.getBytes(256));
        assertThrows(IllegalArgumentException.class, () ->
                service.assemble(request(" nop"), SourceProvider.fileSystem(), Assembler.OutputType.BIN, -1));
    }

    @Test void binAndMzfWriterAndApiIncludeTheSameGapsAndPayloadSize() throws Exception {
        Path main = directory.resolve("disk.asm"), output = directory.resolve("disk.out");
        Files.writeString(main, " mzf_load $1200\n org $1200\n db $AA\n org $1204\n db $BB");
        for (int fill : List.of(0, 255)) {
            for (var type : Assembler.OutputType.values()) {
                new Assembler(main, List.of(), fill).writeObject(output, type);
                var result = service.assemble(new AssemblyRequest(main), SourceProvider.fileSystem(), type, fill);
                assertTrue(result.succeeded(), result.diagnostics().toString());
                byte[] object = result.objectCode();
                assertArrayEquals(Files.readAllBytes(output), object);
                int prefix = type == Assembler.OutputType.MZF ? 128 : 0;
                assertArrayEquals(b(0xAA, fill, fill, fill, 0xBB),
                        Arrays.copyOfRange(object, prefix, object.length));
                if (prefix != 0)
                    assertEquals(5, (object[18] & 255) | (object[19] & 255) << 8);
            }
        }
    }

    @Test void commandLineAndApiEmitIdenticalBinAndMzfWithConfiguredFill() throws Exception {
        Path main = directory.resolve("cli.asm");
        Files.writeString(main, " mzf_load $1200\n org $1200\n db $AA\n org $1204\n db $BB");
        for (var type : Assembler.OutputType.values()) {
            Path output = directory.resolve("cli." + (type == Assembler.OutputType.MZF ? "mzf" : "bin"));
            runCli(0, "-F", "0xFF", "-O", type.name(), main.toString(), output.toString());
            var result = service.assemble(new AssemblyRequest(main), SourceProvider.fileSystem(), type, 255);
            assertTrue(result.succeeded(), result.diagnostics().toString());
            assertArrayEquals(result.objectCode(), Files.readAllBytes(output));
        }
    }

    @Test void commandLineRejectsBackwardOriginAndInvalidFill() throws Exception {
        Path main = directory.resolve("cli-bad.asm"), output = directory.resolve("bad.bin");
        Files.writeString(main, " org $1200\n db 1,2\n org $1201\n db 3");
        assertTrue(runCli(1, main.toString(), output.toString()).contains("ORG overlaps earlier output"));
        assertTrue(runCli(1, "-F", "256", main.toString(), output.toString()).contains("ORG fill must be a byte"));
    }

    private String runCli(int expectedExit, String... arguments) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(Assembler.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var command = new java.util.ArrayList<>(List.of(java, "-cp", classes, "org.tbc.glass.Assembler"));
        command.addAll(List.of(arguments));
        Path log = directory.resolve("cli.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Assembler process did not finish");
            String text = Files.readString(log);
            assertEquals(expectedExit, process.exitValue(), text);
            return text;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
