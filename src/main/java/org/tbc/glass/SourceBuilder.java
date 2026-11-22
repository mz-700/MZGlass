package org.tbc.glass;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.tbc.glass.SourceFile.SourceFileSpan;
import org.tbc.glass.api.Diagnostic;

import org.tbc.glass.directives.Directive;
import org.tbc.glass.directives.Ds;
import org.tbc.glass.directives.Equ;
import org.tbc.glass.directives.If;
import org.tbc.glass.directives.Incbin;
import org.tbc.glass.directives.Include;
import org.tbc.glass.directives.Instruction;
import org.tbc.glass.directives.Irp;
import org.tbc.glass.directives.Macro;
import org.tbc.glass.directives.Proc;
import org.tbc.glass.directives.Rel;
import org.tbc.glass.directives.Rept;
import org.tbc.glass.directives.Section;
import org.tbc.glass.directives.Struct;
import org.tbc.glass.directives.Terminator;
import org.tbc.glass.expressions.Expression;
import org.tbc.glass.expressions.Sequence;
import org.tbc.glass.expressions.Type;
import org.tbc.glass.instructions.ArgumentException;

public class SourceBuilder {

	private static final List<String> END_TERMINATORS = Arrays.asList("end", "END");
	private static final List<String> ENDM_TERMINATORS = Arrays.asList("endm", "ENDM");
	private static final List<String> ENDP_TERMINATORS = Arrays.asList("endp", "ENDP");
	private static final List<String> ENDS_TERMINATORS = Arrays.asList("ends", "ENDS");
	private static final List<String> ENDSTRUCT_TERMINATORS = Arrays.asList("endstruct", "ENDSTRUCT");
	private static final List<String> ENDREL_TERMINATORS = Arrays.asList("endrel", "ENDREL");
	private static final List<String> ELSE_TERMINATORS = Arrays.asList("else", "ELSE", "endif", "ENDIF");
	private static final List<String> ENDIF_TERMINATORS = Arrays.asList("endif", "ENDIF");

	private final Source source;
	private final List<String> terminators;
	private final List<Path> includePaths;

	private final SourceSession session;
	private final Recovery recovery;
	private final DotLocalLabels dotLabels;

	private record OperandCheck(Line line, Consumer<AssemblyException> report) {}

	private record Recovery(Consumer<AssemblyException> report, List<OperandCheck> operands) {
		Recovery includedAt(SourceFileSpan site) {
			return new Recovery(error -> {
				error.addContext(site);
				report.accept(error);
			}, operands);
		}

		Recovery inBlock() {
			// The block may be inactive or require macro arguments to be checked.
			return new Recovery(report, null);
		}
	}

	public SourceBuilder(List<Path> includePaths) {
		this(includePaths, SourceProvider.fileSystem());
	}

	public SourceBuilder(List<Path> includePaths, SourceProvider sourceProvider) {
		this(includePaths, sourceProvider, null);
	}

	/** A diagnostic consumer captures warnings; null retains the command-line output. */
	public SourceBuilder(List<Path> includePaths, SourceProvider sourceProvider, Consumer<Diagnostic> diagnostics) {
		this(includePaths,sourceProvider,diagnostics,false);
	}

	/** Opt-in IDE policy; default constructors preserve standalone parsing. */
	public SourceBuilder(List<Path> includePaths, SourceProvider sourceProvider, Consumer<Diagnostic> diagnostics, boolean ignoreMzfDirectives) {
		this(includePaths, sourceProvider, diagnostics, ignoreMzfDirectives, null);
	}

	public static SourceBuilder forDiagnostics(List<Path> includePaths, SourceProvider sourceProvider,
			Consumer<Diagnostic> diagnostics, boolean ignoreMzfDirectives, Consumer<AssemblyException> parseErrors) {
		return new SourceBuilder(includePaths, sourceProvider, diagnostics, ignoreMzfDirectives,
				new Recovery(Objects.requireNonNull(parseErrors), new ArrayList<>()));
	}

	private SourceBuilder(List<Path> includePaths, SourceProvider sourceProvider, Consumer<Diagnostic> diagnostics,
			boolean ignoreMzfDirectives, Recovery recovery) {
		this(new Scope(new GlobalScope()), END_TERMINATORS, List.copyOf(includePaths),
				new SourceSession(Objects.requireNonNull(sourceProvider, "sourceProvider"), diagnostics,ignoreMzfDirectives), recovery,
				new DotLocalLabels());
	}

	private SourceBuilder(Scope scope, List<String> terminators, List<Path> includePaths, SourceSession session, Recovery recovery,
			DotLocalLabels dotLabels) {
		this.source = new Source(scope, recovery == null ? null : recovery.report());
		this.terminators = terminators;
		this.includePaths = includePaths;
		this.session = session;
		this.recovery = recovery;
		this.dotLabels = dotLabels;
	}

	public boolean hasLoadedSourceFile(Path path) {
		return session.hasLoaded(path);
	}

	private List<Path> getIncludePaths(SourceFile sourceFile) {
		List<Path> basePaths = new ArrayList<>();
		Path path = sourceFile.getPath();
		if (path != null)
			basePaths.add(path.resolveSibling(path.getFileSystem().getPath("")));
		basePaths.addAll(includePaths);
		return basePaths;
	}

	private Source parseInclude(Expression sourcePath, SourceFile baseSourceFile, boolean once) {
		for (Path includePath : getIncludePaths(baseSourceFile)) {
			Path fullPath = includePath.resolve(sourcePath.getString());
			if (session.exists(fullPath)) {
				if (once && hasLoadedSourceFile(fullPath))
					return null;
				return parse(fullPath);
			}
		}
		var missing = new AssemblyException("Include file not found: " + sourcePath.getString());
		if (recovery == null)
			throw missing;
		recovery.report().accept(missing);
		return null;
	}

	public Source parse(Path sourcePath) {
		return parse(session.readSource(sourcePath));
	}

	public Source parse(SourceFile sourceFile) {
		return parse(new Parser(sourceFile));
	}

	public Source parse(Parser parser) {
		session.enter(parser.getSourceFile());
		try {
			return parseBody(parser);
		} finally {
			session.leave(parser.getSourceFile());
		}
	}

	private Source parseBody(Parser parser) {
		parser.ignoreMzfDirectives(session.ignoreMzfDirectives);
		while (true) {
			Line line = recovery == null ? parser.parse(source.getScope(), dotLabels)
					: parser.parseRecovering(source.getScope(), dotLabels, recovery.report());
			line.setDiagnosticConsumer(session.diagnostics);

			try {
				Directive directive = getDirective(line, parser);
				line.setDirective(directive);
				source.addLine(line);
				if (recovery != null && recovery.operands() != null && directive instanceof Instruction && !line.isIgnoredInstruction())
					recovery.operands().add(new OperandCheck(line, recovery.report()));
				if (directive instanceof Terminator)
					return source;
			} catch (AssemblyException e) {
				e.addContext(line.getSourceSpan());
				throw e;
			}
		}
	}

	/** Check independent built-in operand forms on a recovered tree, without resolving addresses or emitting bytes. */
	public void validateRecoveredOperands() {
		if (recovery == null)
			throw new IllegalStateException("Operand recovery requires a diagnostics source builder.");
		Scope builtins = source.getScope().getParent();
		for (OperandCheck check : recovery.operands()) {
			Line line = check.line();
			String mnemonic = line.getMnemonic();
			try {
				if (!builtins.hasLocalSymbol(mnemonic) || line.getScope().getSymbol(mnemonic) != builtins.getLocalSymbol(mnemonic))
					continue;
				line.getInstruction().createObject(line.getScope(), line.getArguments());
			} catch (ArgumentException error) {
				error.addContext(line.getSourceSpan());
				check.report().accept(error);
			} catch (AssemblyException dependentError) {
				// Evaluation may depend on a skipped statement or an address that has yet to be assigned.
			}
		}
	}

	public Directive getDirective(Line line, Parser parser) {
		switch (line.getMnemonic()) {
		case "equ":
		case "EQU":
			return new Equ();
		case "include":
		case "INCLUDE":
			return getIncludeDirective(line, parser.getSourceFile());
		case "incbin":
		case "INCBIN":
			return new Incbin(getIncludePaths(parser.getSourceFile()), session);
		case "macro":
		case "MACRO":
			return new Macro(parseBlock(line.getScope(), ENDM_TERMINATORS, parser));
		case "struct":
		case "STRUCT":
			return new Struct(parseBlock(line.getScope(), ENDSTRUCT_TERMINATORS, parser));
		case "rept":
		case "REPT":
			return new Rept(parseBlock(line.getScope(), ENDM_TERMINATORS, parser));
		case "irp":
		case "IRP":
			return new Irp(parseBlock(line.getScope(), ENDM_TERMINATORS, parser));
		case "proc":
		case "PROC":
			return new Proc(parseBlock(line.getScope(), ENDP_TERMINATORS, parser));
		case "rel":
		case "REL":
			return new Rel(parseBlock(line.getScope(), ENDREL_TERMINATORS, parser));
		case "if":
		case "IF":
			Source thenBlock = parseBlock(new Scope(line.getScope()), ELSE_TERMINATORS, parser, copyDotLabels());
			Source elseBlock = !ENDIF_TERMINATORS.contains(thenBlock.getLastLine().getMnemonic()) ?
					parseBlock(new Scope(line.getScope()), ENDIF_TERMINATORS, parser, copyDotLabels()) : new Source(new Scope(line.getScope()));
			return new If(thenBlock, elseBlock);
		case "section":
		case "SECTION":
			return new Section(parseBlock(source.getScope(), ENDS_TERMINATORS, parser, copyDotLabels()));
		case "ds":
		case "DS":
			return new Ds();
		case "end":
		case "END":
		case "endm":
		case "ENDM":
		case "endp":
		case "ENDP":
		case "ends":
		case "ENDS":
		case "endstruct":
		case "ENDSTRUCT":
		case "endrel":
		case "ENDREL":
		case "else":
		case "ELSE":
		case "endif":
		case "ENDIF":
			if (!terminators.contains(line.getMnemonic())) {
				if ( line.getMnemonic()
                         .equals( "end" ) || line.getMnemonic()
                                                 .equals( "END" ) )
					throw new AssemblyException( "Unexpected end of file. Expecting: " + terminators );
				throw new AssemblyException("Unexpected " + line.getMnemonic() + ".");
			}
			return new Terminator();
		default:
			return new Instruction();
		}
	}

	private Directive getIncludeDirective(Line line, SourceFile sourceFile) {
		boolean once = false;
		Expression argument = line.getArguments();
		if (argument.is(Type.ANNOTATION)) {
			String annotation = argument.getAnnotation().getName();
			if ("once".equals(annotation) || "ONCE".equals(annotation)) {
				argument = argument.getAnnotee();
				once = true;
			}
		}
		if (line.getArguments() instanceof Sequence)
			throw new AssemblyException("Include only accepts 1 argument.");
		if (!argument.is(Type.STRING))
			throw new AssemblyException("A string literal is expected.");
		Recovery included = recovery == null ? null : recovery.includedAt(line.getSourceSpan());
		SourceBuilder sourceBuilder = new SourceBuilder(source.getScope(), END_TERMINATORS, includePaths, session, included,
				dotLabels == null ? null : new DotLocalLabels());
		sourceBuilder.parseInclude(argument, sourceFile, once);
		return new Include(sourceBuilder.source);
	}

	private Source parseBlock(Scope scope, List<String> terminators, Parser parser) {
		return parseBlock(scope, terminators, parser, null);
	}

	private Source parseBlock(Scope scope, List<String> terminators, Parser parser, DotLocalLabels labels) {
		return new SourceBuilder(scope, terminators, includePaths, session, recovery == null ? null : recovery.inBlock(), labels).parseBody(parser);
	}

	private DotLocalLabels copyDotLabels() {
		return dotLabels == null ? null : dotLabels.copy();
	}

}
