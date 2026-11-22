package org.tbc.glass;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tbc.glass.api.AssemblyRequest;
import org.tbc.glass.api.AssemblyService;

import static org.junit.jupiter.api.Assertions.*;

class DotLocalLabelsTest {
    @TempDir Path directory;

    private final AssemblyService service = new AssemblyService();

    @Test void bindsForwardReferencesAndQualifiedReferencesToTheirOwnLabels() {
        var request = request(" org $1000\nfirst: jr .loop\n.loop: nop\nsecond: jr .loop\n.loop: ret\n dw first.loop,second.loop\n");
        var result = service.assemble(request);
        assertTrue(result.succeeded(), result.diagnostics().toString());
        assertArrayEquals(new byte[]{0x18, 0, 0, 0x18, 0, (byte) 0xc9, 2, 0x10, 5, 0x10}, result.objectCode());
        assertTrue(service.analyze(request, SourceProvider.fileSystem()).succeeded());
    }

    @Test void rejectsDuplicatesUnderOneOwnerAndReferencesToAnotherOwnersLocal() {
        var duplicate = service.assemble(request("first: nop\n.loop: nop\n.loop: ret\n"));
        assertFalse(duplicate.succeeded());
        assertEquals("Can not redefine symbol: first.loop", duplicate.diagnostics().getFirst().message());

        var unresolved = service.assemble(request("first: nop\n.loop: nop\nsecond: jr .loop\n"));
        assertFalse(unresolved.succeeded());
        assertEquals("Symbol not found: second.loop", unresolved.diagnostics().getFirst().message());
    }

    @Test void includesStartTheirOwnOwnerAndLeaveTheCallersOwnerIntact() {
        Path main = directory.resolve("main.asm"), child = directory.resolve("nested.inc");
        var request = new AssemblyRequest(main, List.of(), Map.of(
                main, "outer: nop\n include \"nested.inc\"\n.local: nop\n dw outer.local,child.local\n",
                child, "child: nop\n.local: nop\n"));
        var result = service.assemble(request);
        assertTrue(result.succeeded(), result.diagnostics().toString());
        assertArrayEquals(new byte[]{0, 0, 0, 0, 3, 0, 2, 0}, result.objectCode());
    }

    @Test void conditionalBranchesInheritTheOwnerAndRestoreItAfterward() {
        var result = service.assemble(request("outer: nop\n if 1\n jp .done\n else\nother: nop\n.done: ret\n endif\n.done: ret\n"));
        assertTrue(result.succeeded(), result.diagnostics().toString());
        assertArrayEquals(new byte[]{0, (byte) 0xc3, 4, 0, (byte) 0xc9}, result.objectCode());
    }

    @Test void explicitProceduresRetainTheirExistingLocalAndQualifiedNames() {
        var result = service.assemble(request("first: proc\n.loop: nop\ninside: jr .loop\n endp\n"
                + "second: proc\n.loop: ret\n jp .loop\n endp\n dw first..loop,second..loop\n"));
        assertTrue(result.succeeded(), result.diagnostics().toString());
        assertArrayEquals(new byte[]{0, 0x18, (byte) 0xfd, (byte) 0xc9, (byte) 0xc3, 3, 0, 0, 0, 3, 0}, result.objectCode());
    }

    private AssemblyRequest request(String text) {
        Path main = directory.resolve("main.asm");
        return new AssemblyRequest(main, List.of(), Map.of(main, text));
    }
}
