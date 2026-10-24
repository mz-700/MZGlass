package org.tbc.glass.directives;

import java.nio.file.Path;
import java.util.List;

import org.tbc.glass.Line;
import org.tbc.glass.Scope;
import org.tbc.glass.SourceProvider;

public class Incbin extends Directive {

	private final List<Path> basePaths;
	private final SourceProvider sourceProvider;

	public Incbin(List<Path> basePaths) {
		this(basePaths, SourceProvider.fileSystem());
	}

	public Incbin(List<Path> basePaths, SourceProvider sourceProvider) {
		this.basePaths = basePaths;
		this.sourceProvider = sourceProvider;
	}

	@Override
	public void register(Scope scope, Line line) {
		line.setInstruction(new org.tbc.glass.instructions.Incbin(basePaths, sourceProvider));
		super.register(scope, line);
	}

}
