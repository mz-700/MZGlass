package org.tbc.glass;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tbc.glass.api.SourceRange;

class SourceFileTest {
    @TempDir Path directory;

    @Test void diskAndUnsavedSourcesUseTheSameUtf8AndLineEndings() throws IOException {
        Path path = directory.resolve("utf8.asm"); String text = "; café 🚀 日本語\r\n db 1\r\n";
        Files.writeString(path, text);
        SourceFile disk = new SourceFile(path), snapshot = new SourceFile(path, text);
        assertEquals(path, snapshot.getPath());
        assertEquals(disk.getText(), snapshot.getText());
        assertEquals("; café 🚀 日本語\n db 1\n", disk.getText());
        assertEquals(3, disk.getLineCount());
        assertEquals(disk.getText().indexOf(" db"), disk.getLineStartOffset(1));
    }

    @Test void spansExposeLinesAndColumnsWithoutParsingErrorMessages() {
        SourceFile source = new SourceFile(directory.resolve("main.asm"), "first\nsecond");
        var span = source.new SourceFileSpan(0, 2, 2);
        assertEquals(0, span.getLineStart()); assertEquals(2, span.getLineEnd()); assertEquals(2, span.getColumn());
        assertEquals(new SourceRange(source.getPath(), 8, 9), span.toRange());
        assertEquals(new SourceRange(source.getPath(), 0, 12), source.new SourceFileSpan(0, 2).toRange());
    }

    @Test void eofAndEmptyFileRangesAreClampedAndCanBeZeroWidth() {
        SourceFile source = new SourceFile(" abc");
        assertEquals(new SourceRange(null, 4, 4), source.new SourceFileSpan(1, 1, 99).toRange());
        assertEquals(new SourceRange(null, 4, 4), source.new SourceFileSpan(0, 1, 99).toRange());
        assertEquals(new SourceRange(null, 0, 0), new SourceFile("").new SourceFileSpan(0, 1).toRange());
    }

    @Test void aPointRangeDoesNotSplitASurrogatePair() {
        SourceFile source = new SourceFile(" 🚀");
        assertEquals(new SourceRange(null, 1, 3), source.new SourceFileSpan(0, 1, 1).toRange());
    }

    @Test void readFailuresRetainTheirIoCause() {
        AssemblyException error = assertThrows(AssemblyException.class, () -> new SourceFile(directory.resolve("missing.asm")));
        assertInstanceOf(IOException.class, error.getCause());
    }
}
