package org.tbc.glass.api;

import java.util.Objects;

/** Range in the raw payload, before any container header. Logical and file addresses may differ under PHASE. */
public record Emission(int offset, int length, long logicalAddress, Kind kind, SourceRange source) {
    public enum Kind { DATA, ORIGIN_PADDING }
    public Emission {
        if(offset<0 || length<=0) throw new IllegalArgumentException("Invalid emission range");
        Objects.requireNonNull(kind); Objects.requireNonNull(source);
    }
    public Emission shifted(int offset) { return new Emission(this.offset+offset,length,logicalAddress,kind,source); }
}
