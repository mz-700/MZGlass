package org.tbc.glass;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tbc.glass.api.AssemblyRequest;
import org.tbc.glass.api.AssemblyResult;
import org.tbc.glass.api.AssemblyService;
import org.tbc.glass.api.AssemblyService.ErrorMode;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleRegisterOutputTest {
    @TempDir Path directory;

    @Test void encodesEverySelectorInOrderAndCountsThreeBytesPerInstruction() {
        String[] registers = ("A B C D E F H L A' B' C' D' E' F' H' L' AF BC DE HL "
                + "AF' BC' DE' HL' IX IY IXL IXH IYL IYH PC SP I R").split(" ");
        var source = new StringBuilder(" ORG $1200\n JR after\n");
        source.append(" C_ROUT ").append(registers[0]).append('\n').append("after:\n");
        for (int i = 1; i < registers.length; i++) {
            source.append(" c_rout ").append(registers[i].toLowerCase(Locale.ROOT)).append('\n');
        }
        var expected = new byte[2 + 3 * registers.length];
        expected[0] = 0x18;
        expected[1] = 3;
        for (int i = 0; i < registers.length; i++) {
            expected[2 + 3 * i] = (byte) 0xed;
            expected[4 + 3 * i] = (byte) i;
        }
        var result = assemble(source.toString());
        assertTrue(result.succeeded(), result.diagnostics().toString());
        assertArrayEquals(expected, result.objectCode());
    }

    @Test void rejectsMissingNumericIndirectAndMultipleOperands() {
        for (String operand : List.of("", "$21", "(HL)", "A,B", "AF''")) {
            assertFalse(assemble(" C_ROUT " + operand).succeeded(), operand);
        }
        var result = assemble("pc: EQU 42\n DB pc\n C_ROUT PC\n");
        assertTrue(result.succeeded(), result.diagnostics().toString());
        assertArrayEquals(new byte[] {42, (byte) 0xed, 0, 0x1e}, result.objectCode());
    }

    @Test void encodesMemoryOutputWithSharedSelectorsAndADeferredLength() {
        String[] registers = {"BC", "DE", "HL", "IX", "IY", "BC'", "DE'", "HL'", "SP", "PC"};
        int[] selectors = {0x11, 0x12, 0x13, 0x18, 0x19, 0x15, 0x16, 0x17, 0x1f, 0x1e};
        var text = new StringBuilder(" ORG $1200\n JR after\n C_MOUT HL, 0\nafter:\n");
        var expected = new byte[6 + registers.length * 4];
        expected[0] = 0x18;
        expected[1] = 4;
        expected[2] = (byte) 0xed;
        expected[3] = 1;
        expected[4] = 0x13;
        for (int i = 0; i < registers.length; i++) {
            text.append(" c_mout ").append(registers[i].toLowerCase(Locale.ROOT)).append(", length\n");
            int at = 6 + i * 4;
            expected[at] = (byte) 0xed;
            expected[at + 1] = 1;
            expected[at + 2] = (byte) selectors[i];
            expected[at + 3] = (byte) 255;
        }
        text.append("length: EQU 250+5\n");
        var result = assemble(text.toString());
        assertTrue(result.succeeded(), result.diagnostics().toString());
        assertArrayEquals(expected, result.objectCode());
    }

    @Test void rejectsUnsupportedMemoryOperandsAndLengths() {
        for (String operands : List.of("AF,1", "A,1", "R,1", "(HL),1", "$13,1", "HL", "HL,1,2",
                "HL,-1", "HL,256", "HL,A")) {
            assertFalse(assemble(" C_MOUT " + operands).succeeded(), operands);
        }
    }

    @Test void encodesStringOutputFollowedByInlineData() {
        var result = assemble("""
                 ORG $1200
                 JR after
                 C_SOUT
                 DB "Hi",$0D
                after: c_sout
                 DB $0D
                """);
        assertTrue(result.succeeded(), result.diagnostics().toString());
        assertArrayEquals(new byte[] {0x18, 5, (byte) 0xed, 2, 'H', 'i', 0x0d, (byte) 0xed, 2, 0x0d},
                result.objectCode());
        assertFalse(assemble(" C_SOUT HL").succeeded());
    }

    @Test void encodesConsoleRegisterInputWithTheSameSelectorsAsOutput() {
        var source = new StringBuilder(" ORG $1200\n JR after\n C_RIN PC\nafter:\n");
        var names = org.tbc.glass.instructions.ConsoleRegisterOutput.registerNames();
        for (String name : names) source.append(" c_rin ").append(name.toLowerCase(Locale.ROOT)).append('\n');
        var result = assemble(source.toString());
        assertTrue(result.succeeded(), result.diagnostics().toString());
        byte[] bytes = result.objectCode();
        assertEquals(5 + 3 * names.size(), bytes.length);
        assertArrayEquals(new byte[] {0x18, 3, (byte) 0xed, 3, 0x1e}, java.util.Arrays.copyOf(bytes, 5));
        for (int index = 0; index < names.size(); index++) {
            int offset = 5 + index * 3;
            assertArrayEquals(new byte[] {(byte) 0xed, 3, (byte) index},
                    java.util.Arrays.copyOfRange(bytes, offset, offset + 3), names.get(index));
        }
        for (String operand : List.of("", "1", "(HL)", "A,B")) assertFalse(assemble(" C_RIN " + operand).succeeded(), operand);
    }

    @Test void encodesMemoryInputWithTheSameOperandsAsMemoryOutput() {
        var source = new StringBuilder();
        var registers = org.tbc.glass.instructions.ConsoleMemoryOutput.registerNames();
        for (String register : registers) {
            source.append(" C_MOUT ").append(register).append(",limit\n");
            source.append(" c_min ").append(register).append(",limit\n");
        }
        source.append(" C_MIN HL,0\nlimit: EQU 255\n");
        var result = assemble(source.toString());
        assertTrue(result.succeeded(), result.diagnostics().toString());
        byte[] bytes = result.objectCode();
        assertEquals(8 * registers.size() + 4, bytes.length);
        for (int offset = 0; offset < 8 * registers.size(); offset += 8) {
            assertEquals((byte) 0xed, bytes[offset + 4]);
            assertEquals(4, bytes[offset + 5]);
            assertEquals(bytes[offset + 2], bytes[offset + 6]);
            assertEquals((byte) 255, bytes[offset + 7]);
        }
        assertArrayEquals(new byte[] {(byte) 0xed, 4, 0x13, 0},
                java.util.Arrays.copyOfRange(bytes, bytes.length - 4, bytes.length));
        for (String operands : List.of("AF,1", "(HL),1", "HL", "HL,-1", "HL,256", "BC,1,2")) {
            assertFalse(assemble(" C_MIN " + operands).succeeded(), operands);
        }
    }

    @Test void encodesStringInputWithTheSameOperandsAsMemoryInput() {
        var source = new StringBuilder();
        var registers = org.tbc.glass.instructions.ConsoleMemoryOutput.registerNames();
        for (String register : registers) {
            source.append(" C_MIN ").append(register).append(",limit\n");
            source.append(" c_sin ").append(register).append(",limit\n");
        }
        source.append(" C_SIN HL,0\nlimit: EQU 255\n");
        var result = assemble(source.toString());
        assertTrue(result.succeeded(), result.diagnostics().toString());
        byte[] bytes = result.objectCode();
        assertEquals(8 * registers.size() + 4, bytes.length);
        for (int offset = 0; offset < 8 * registers.size(); offset += 8) {
            assertEquals((byte) 0xed, bytes[offset + 4]);
            assertEquals(5, bytes[offset + 5]);
            assertEquals(bytes[offset + 2], bytes[offset + 6]);
            assertEquals((byte) 255, bytes[offset + 7]);
        }
        assertArrayEquals(new byte[] {(byte) 0xed, 5, 0x13, 0},
                java.util.Arrays.copyOfRange(bytes, bytes.length - 4, bytes.length));
        for (String operands : List.of("AF,1", "(HL),1", "HL", "HL,-1", "HL,256", "BC,1,2")) {
            assertFalse(assemble(" C_SIN " + operands).succeeded(), operands);
        }
    }

    @Test void nopsAllConsoleInstructionsAndInlineTextWithoutMovingLabels() {
        Path main = directory.resolve("main.asm"), include = directory.resolve("string.inc");
        String program = """
                 ORG $1200
                 JR after
                 C_ROUT A
                 C_MOUT HL,2
                 C_RIN AF'
                 C_MIN HL,2
                 C_SIN HL,3
                 C_SOUT
                 DB "Hi"
                 INCLUDE "string.inc"
                after:
                 LD A,$2A
                 DW after
                """;
        var overlays = Map.of(main, program, include, " DB $0D,$A5\n");
        var active = new AssemblyService().assemble(new AssemblyRequest(main, List.of(), overlays));
        assertTrue(active.succeeded(), active.diagnostics().toString());
        byte[] expected = active.objectCode();
        assertEquals(0x18, expected[0]);
        assertEquals(24, expected[1]);
        assertArrayEquals(new byte[] {0x3e, 0x2a, 0x1a, 0x12}, java.util.Arrays.copyOfRange(expected, 26, 30));
        java.util.Arrays.fill(expected, 2, 25, (byte) 0);
        assertEquals((byte) 0xa5, expected[25]);

        for (var mode : ErrorMode.values()) {
            var portable = new AssemblyService().assemble(new AssemblyRequest(main, List.of(), overlays, false, true),
                    SourceProvider.fileSystem(), Assembler.OutputType.BIN, 0, mode);
            assertTrue(portable.succeeded(), portable.diagnostics().toString());
            assertArrayEquals(expected, portable.objectCode());
            assertEquals(active.emissions(), portable.emissions());
        }
    }

    @Test void nopsSelectedBranchesAndFixedAddressSections() {
        Path main = directory.resolve("section.asm");
        String program = """
                 ORG $1200
                region: DS 8,$AA
                 SECTION region
                 IF 1
                 C_SOUT
                 DB "X",$0D
                 ELSE
                 DB $FF
                 ENDIF
                 C_ROUT A
                 ENDS
                 RET
                """;
        var portable = new AssemblyService().assemble(new AssemblyRequest(main, List.of(), Map.of(main, program), false, true));
        assertTrue(portable.succeeded(), portable.diagnostics().toString());
        assertArrayEquals(new byte[] {0, 0, 0, 0, 0, 0, 0, (byte) 0xaa, (byte) 0xc9}, portable.objectCode());
    }

    @Test void requiresTheStringTerminatorBeforeCodeOrTheEndOfTheOutput() {
        Path main = directory.resolve("unterminated.asm");
        for (String following : List.of(" LD A,$0D\n", "", " ORG $1208\n DB $0D\n")) {
            String program = " ORG $1200\n C_SOUT\n DB \"Hi\"\n" + following;
            assertTrue(assemble(program).succeeded());
            for (var mode : ErrorMode.values()) {
                var portable = new AssemblyService().assemble(new AssemblyRequest(main, List.of(), Map.of(main, program), false, true),
                        SourceProvider.fileSystem(), Assembler.OutputType.BIN, 0, mode);
                assertFalse(portable.succeeded());
                assertEquals(0, portable.objectCode().length);
                assertTrue(portable.diagnostics().getFirst().message().contains("C_SOUT requires inline data terminated by $0D"));
            }
        }
    }

    private AssemblyResult assemble(String source) {
        Path main = directory.resolve("main.asm");
        return new AssemblyService().assemble(new AssemblyRequest(main, List.of(), Map.of(main, source)));
    }
}
