package org.tbc.glass;

/** Tracks the lexical owner of leading-dot labels in ordinary source. */
public final class DotLocalLabels {
    private String owner;

    public String owner() {
        return owner;
    }

    public String declare(String name) {
        if (!name.startsWith("."))
            owner = name;
        return qualify(owner, name);
    }

    public DotLocalLabels copy() {
        var copy = new DotLocalLabels();
        copy.owner = owner;
        return copy;
    }

    public static String qualify(String owner, String name) {
        return owner != null && name.startsWith(".") ? owner + name : name;
    }
}
