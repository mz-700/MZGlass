package org.tbc.glass.api;

import java.nio.file.Path;

/** Zero-based UTF-16 offsets in LF-normalized source text; the end is exclusive.
 * A null path denotes an anonymous source. */
public record SourceRange(Path path, int from, int to) {
    public SourceRange {
        if (from < 0 || to < from) throw new IllegalArgumentException("Invalid source range");
    }
}
