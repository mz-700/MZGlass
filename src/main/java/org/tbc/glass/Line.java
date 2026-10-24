package org.tbc.glass;

import java.util.List;
import java.util.function.Consumer;

import org.tbc.glass.api.Diagnostic;
import org.tbc.glass.SourceFile.SourceFileSpan;
import org.tbc.glass.directives.Directive;
import org.tbc.glass.expressions.Expression;
import org.tbc.glass.instructions.Empty;
import org.tbc.glass.instructions.InstructionFactory;
import org.tbc.glass.instructions.InstructionObject;

public class Line {

	private final Scope scope;
	private final List<String> labels;
	private final String mnemonic;
	private final int mnemonicColumn;
	private final Expression arguments;
	private final String comment;
	private final SourceFileSpan sourceSpan;

	private InstructionFactory instruction;
	private InstructionObject instructionObject;
	private Directive directive;
	private Consumer<Diagnostic> diagnosticConsumer;

	public Line(Scope scope, List<String> labels, String mnemonic, int mnemonicColumn, Expression arguments, String comment, SourceFileSpan sourceSpan) {
		if (mnemonic == null)
			throw new AssemblyException("Missing mnemonic.");
		this.scope = scope;
		this.labels = labels;
		this.mnemonic = mnemonic;
		this.mnemonicColumn = mnemonicColumn;
		this.arguments = arguments;
		this.comment = comment;
		this.sourceSpan = sourceSpan;
	}

	public Line copy(Scope scope) {
		Line newLine = new Line(scope, labels, mnemonic, mnemonicColumn, arguments != null ? arguments.copy(scope) : null, comment, sourceSpan);
		newLine.setDirective(directive.copy(scope));
		newLine.setDiagnosticConsumer(diagnosticConsumer);
		return newLine;
	}

	public Scope getScope() {
		return scope;
	}

	public List<String> getLabels() {
		return labels;
	}

	public String getMnemonic() {
		return mnemonic;
	}

	public Expression getArguments() {
		return arguments;
	}

	public String getComment() {
		return comment;
	}

	public SourceFileSpan getSourceSpan() {
		return sourceSpan;
	}

	void setDiagnosticConsumer(Consumer<Diagnostic> consumer) {
		this.diagnosticConsumer = consumer;
	}

	public void setDirective(Directive directive) {
		this.directive = directive;
	}

	public void setInstruction(InstructionFactory instruction) {
		this.instruction = instruction;
	}

	public InstructionFactory getInstruction() {
		if (instruction == null) {
			try {
				instruction = mnemonic != null ? scope.getSymbol(mnemonic).getInstruction() : Empty.INSTANCE;
			} catch (AssemblyException e) {
				if (mnemonicColumn != -1)
					e.addContext(sourceSpan.atColumn(mnemonicColumn));
				throw e;
			}
		}
		return instruction;
	}

	public InstructionObject getInstructionObject() {
		return instructionObject;
	}

	public void register(Scope sourceScope) {
		try {
			directive.register(sourceScope, this);
		} catch (AssemblyException e) {
			e.addContext(sourceSpan);
			throw e;
		}
	}

	public void expand(List<Line> lines) {
		try {
			getInstruction().expand(this, lines);
		} catch (AssemblyException e) {
			e.addContext(sourceSpan);
			throw e;
		}
	}

	public Expression resolve(Expression address) {
		try {
			instructionObject = getInstruction().createObject(scope, arguments);
			return instructionObject.resolve(address);
		} catch (AssemblyException e) {
			e.addContext(sourceSpan);
			throw e;
		}
	}

	public Expression getSize() {
		try {
			return instructionObject.getSize();
		} catch (AssemblyException e) {
			e.addContext(sourceSpan);
			throw e;
		}
	}

	public byte[] getBytes() {
		try {
			if (diagnosticConsumer != null && instructionObject instanceof org.tbc.glass.instructions.Warning.Warning_ warning)
				return warning.getBytes(message -> diagnosticConsumer.accept(new Diagnostic(
						Diagnostic.Severity.WARNING, message, sourceSpan.toRange(), List.of())));
			return instructionObject.getBytes();
		} catch (AssemblyException e) {
			e.addContext(sourceSpan);
			throw e;
		}
	}

	public String toString() {
		StringBuilder builder = new StringBuilder();
		for (String label : labels) {
			builder.append(label).append(":\n");
		}
		if (mnemonic != null) {
			builder.append("\t").append(mnemonic);
			if (arguments != null)
				builder.append(" ").append(arguments);
		}
		if (comment != null) {
			if (mnemonic != null)
				builder.append(" ");
			builder.append(";").append(comment);
		}
		return builder.toString();
	}

	public String toListingString() {
		StringBuilder builder = new StringBuilder();
		for (String label : labels)
			builder.append(label).append(": ");
		if (mnemonic != null) {
			builder.append("\t").append(mnemonic);
			if (arguments != null)
				builder.append(" ").append(arguments.toListingString());
		}
		if (comment != null) {
			if (mnemonic != null)
				builder.append(" ");
			builder.append(";").append(comment);
		}
		return builder.toString();
	}

}
