package org.tbc.glass;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tbc.glass.api.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IdeAssemblyTest {
    @TempDir Path directory;
    private final AssemblyService service=new AssemblyService();
    private AssemblyResult assemble(String text,boolean ide) {
        Path main=directory.resolve("main.asm");
        return service.assemble(new AssemblyRequest(main,List.of(),Map.of(main,text),ide));
    }
    @Test void metadataPolicySkipsMalformedArgumentsAndUnknownSuffixesButKeepsLabels() {
        String source=" org $1200\nkeep: MzF_load ]\n mzf_bogus \"unterminated\n mzf_title ((,\\\n dw keep\n";
        var result=assemble(source,true);
        assertTrue(result.succeeded(),result.diagnostics().toString());
        assertArrayEquals(new byte[]{0,0x12},result.objectCode());
        assertEquals(1,result.emissions().size());
        assertFalse(assemble(source,false).succeeded());
        assertFalse(assemble(" mzf_load ]\n",false).succeeded());
    }
    @Test void policyAppliesAcrossIncludesMacroBodiesAndConditionalsWithoutHidingOrdinaryErrors() {
        Path main=directory.resolve("main.asm"),included=directory.resolve("part.inc");
        var request=new AssemblyRequest(main,List.of(),Map.of(main," include \"part.inc\"\n org $1200\n emit\n if 1\n mzf_foo +]\n endif\n",
                included,"emit: macro\n mzf_start \"bad\n nop\n endm\n"),true);
        assertTrue(service.assemble(request).succeeded());
        assertFalse(assemble(" mzf_load ]\n ld a,]\n",true).succeeded());
        assertArrayEquals(new byte[]{'m','z','f','_','x'},assemble(" db \"mzf_x\" ; mzf_load ]\n",true).objectCode());
        assertArrayEquals(new byte[]{0},assemble("mzf_label: nop\n",true).objectCode());
    }
    @Test void layoutDistinguishesPhysicalGapsAndLogicalPhaseAddresses() {
        var result=assemble(" org $1200\n db 1\n org $1204\n phase $3000\n db 2,3\n dephase\n",true);
        assertTrue(result.succeeded()); assertEquals(6,result.objectCode().length);
        var ranges=result.emissions(); assertEquals(3,ranges.size());
        assertEquals(0x1200,ranges.get(0).logicalAddress());
        assertEquals(1,ranges.get(1).offset()); assertEquals(3,ranges.get(1).length());
        assertEquals(Emission.Kind.ORIGIN_PADDING,ranges.get(1).kind()); assertEquals(0x1201,ranges.get(1).logicalAddress());
        assertEquals(4,ranges.get(2).offset()); assertEquals(0x3000,ranges.get(2).logicalAddress());
        assertThrows(UnsupportedOperationException.class,()->ranges.clear());
    }
    @Test void initialPaddingHasAnOriginLocationAndNestedSectionMetadataRetainsPhaseAndVirtualSemantics() {
        var result=assemble(" org $E700\nROM: ds $102,$FF\n section ROM\n org $E800\n phase $3000\n nop\n dephase\n ends\n",true);
        assertTrue(result.succeeded(),result.diagnostics().toString());
        assertEquals(Emission.Kind.ORIGIN_PADDING,result.emissions().getFirst().kind());
        assertEquals(0x100,result.emissions().get(1).offset()); assertEquals(0x3000,result.emissions().get(1).logicalAddress());
        assertEquals(Emission.Kind.DATA,result.emissions().getLast().kind());
        var virtual=assemble(" org $1200\n ds virtual 8\n db 7\n",true);
        assertTrue(virtual.succeeded(),virtual.diagnostics().toString()); assertEquals(1,virtual.objectCode().length); assertEquals(1,virtual.emissions().size());
    }
    @Test void cliDefaultsRetainMzfMetadataValidationAndTheSharedEncoder() {
        Path main=directory.resolve("main.asm");
        var result=service.assemble(new AssemblyRequest(main,List.of(),Map.of(main," mzf_load $1200\n mzf_title test\n nop\n")),SourceProvider.fileSystem(),Assembler.OutputType.MZF);
        assertTrue(result.succeeded(),result.diagnostics().toString()); assertEquals(129,result.objectCode().length);
        assertEquals(1,result.objectCode()[18]); assertEquals(0x12,result.objectCode()[21]);
        assertEquals(1,result.emissions().getFirst().length());
        assertFalse(service.assemble(new AssemblyRequest(main,List.of(),Map.of(main," nop\n")),SourceProvider.fileSystem(),Assembler.OutputType.MZF).succeeded());
    }
}
