package org.tbc.glass;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.tbc.glass.expressions.Expression;
import org.tbc.glass.expressions.IntegerLiteral;
import org.tbc.glass.api.Emission;
import org.tbc.glass.instructions.ConsoleMemoryInput;
import org.tbc.glass.instructions.ConsoleMemoryOutput;
import org.tbc.glass.instructions.ConsoleRegisterInput;
import org.tbc.glass.instructions.ConsoleRegisterOutput;
import org.tbc.glass.instructions.ConsoleStringInput;
import org.tbc.glass.instructions.ConsoleStringOutput;
import org.tbc.glass.instructions.Db;
import org.tbc.glass.instructions.Dd;
import org.tbc.glass.instructions.Dw;
import org.tbc.glass.instructions.Incbin;
import org.tbc.glass.instructions.MzAsc;
import org.tbc.glass.instructions.MzDisp;

public class Source {

	private final Scope scope;
	private final Consumer<AssemblyException> registrationErrors;
	private final MzfConfig mzfConfig = new MzfConfig();
	private List<Line> lines = new ArrayList<>();
	private Expression startAddress = IntegerLiteral.ZERO;

	public Source(Scope scope) {
		this(scope, null);
	}

	Source(Scope scope, Consumer<AssemblyException> registrationErrors) {
		this.scope = scope;
		this.registrationErrors = registrationErrors;
	}

	public Scope getScope() {
		return scope;
	}

	public List<Line> getLines() {
		return lines;
	}

	public MzfConfig getMzfConfig() {
		return mzfConfig;
	}

	public Line getLastLine() {
		return !lines.isEmpty() ? lines.getLast() : null;
	}

	public Source copy(Scope scope) {
		Source newSource = new Source(scope);
		for (Line line : lines)
			newSource.addLine(line.copy(new Scope(scope)));
		return newSource;
	}

	public void addLine(Line line) {
		lines.add(line);
	}

	public void addLines(List<Line> lines) {
		this.lines.addAll(lines);
	}

	public void assemble(OutputStream output) throws IOException {
		assemble(output, 0);
	}

	public void assemble(OutputStream output, int originFill) throws IOException {
		register();
		expand();
		resolve();

		byte[] objectCode = getBytes(originFill);
		output.write(objectCode, 0, objectCode.length);
	}

	public void register() {
		register(scope);
	}

	public void register(Scope scope) {
		for (Line line : lines) {
			try {
				line.register(scope);
			} catch (AssemblyException error) {
				if (registrationErrors == null)
					throw error;
				registrationErrors.accept(error);
			}
		}
	}

	public void expand() {
		List<Line> newLines = new ArrayList<>();
		expand(newLines);
		lines = newLines;
	}

	public void expand(List<Line> newLines) {
		for (Line line : lines)
			line.expand(newLines);
	}

	public Expression resolve() {
		return resolve(IntegerLiteral.ZERO);
	}

	public Expression resolve(Expression address) {
		startAddress = address;
		Expression physicalAddress = address;
		Expression phaseAddress = null;
		Expression phaseStartAddress = null;
		Integer phaseSizeLimit = null;
		Line phaseLine = null;
		for (Line line : lines)
		{
			Expression activeAddress = phaseAddress != null ? phaseAddress : physicalAddress;
			Expression nextAddress = line.resolve(activeAddress);
			updateMzfConfig(line);

			if (line.getInstructionObject() instanceof org.tbc.glass.instructions.Phase.PhaseObject) {
				if (phaseAddress != null) {
					AssemblyException exception = new AssemblyException("Already in phase.");
					exception.addContext(line.getSourceSpan());
					throw exception;
				}
				phaseAddress = nextAddress;
				phaseStartAddress = nextAddress;
				org.tbc.glass.instructions.Phase.PhaseObject phaseObject =
					( org.tbc.glass.instructions.Phase.PhaseObject)line.getInstructionObject();
				phaseSizeLimit = phaseObject.hasSize() ? phaseObject.getSizeLimit() : null;
				checkPhaseRange(phaseStartAddress, phaseSizeLimit, line);
				phaseLine = line;
			} else if (line.getInstructionObject() instanceof org.tbc.glass.instructions.Dephase.DephaseObject) {
				if (phaseAddress == null) {
					AssemblyException exception = new AssemblyException("Dephase without phase.");
					exception.addContext(line.getSourceSpan());
					throw exception;
				}
				phaseAddress = null;
				phaseStartAddress = null;
				phaseSizeLimit = null;
				phaseLine = null;
			} else if (line.getInstructionObject() instanceof org.tbc.glass.instructions.Org.Org_N) {
				if (phaseAddress != null) {
					phaseAddress = nextAddress;
					checkPhaseSize(phaseStartAddress, phaseAddress, phaseSizeLimit, line);
				} else {
					physicalAddress = nextAddress;
				}
			} else if (phaseAddress != null) {
				phaseAddress = nextAddress;
				checkPhaseSize(phaseStartAddress, phaseAddress, phaseSizeLimit, line);
			} else {
				physicalAddress = nextAddress;
			}
		}
		if (phaseAddress != null) {
			AssemblyException exception = new AssemblyException("Phase without dephase.");
			exception.addContext(phaseLine.getSourceSpan());
			throw exception;
		}
		return physicalAddress;
	}

	private void checkPhaseSize(Expression phaseStartAddress, Expression phaseAddress, Integer phaseSizeLimit, Line line) {
		if (phaseSizeLimit != null && phaseAddress.getInteger() - phaseStartAddress.getInteger() > phaseSizeLimit) {
			AssemblyException exception = new AssemblyException("Phase size exceeded.");
			exception.addContext(line.getSourceSpan());
			throw exception;
		}
	}

	private void checkPhaseRange(Expression phaseStartAddress, Integer phaseSizeLimit, Line line) {
		if (phaseSizeLimit != null && phaseStartAddress.getInteger() + phaseSizeLimit > 0x10000) {
			AssemblyException exception = new AssemblyException("Phase range exceeds 64KB address space.");
			exception.addContext(line.getSourceSpan());
			throw exception;
		}
	}

	private void updateMzfConfig(Line line) {
		if(line.isIgnoredInstruction()) return;
		String mnemonic = line.getMnemonic();
		if ("mzf_title".equals(mnemonic) || "MZF_TITLE".equals(mnemonic)) {
			mzfConfig.title = line.getArguments().getString();
		} else if ("mzf_comments".equals(mnemonic) || "MZF_COMMENTS".equals(mnemonic)) {
			mzfConfig.comments = line.getArguments().getString();
		} else if ("mzf_load".equals(mnemonic) || "MZF_LOAD".equals(mnemonic)) {
			mzfConfig.loadAddress = line.getArguments().getInteger();
		} else if ("mzf_start".equals(mnemonic) || "MZF_START".equals(mnemonic)) {
			mzfConfig.startAddress = line.getArguments().getInteger();
		}
	}

	public byte[] getBytes() {
		return getBytes(0);
	}

	/** Emits origin gaps with the supplied byte, without changing source/DS data. */
	public byte[] getBytes(int originFill) {
		ObjectCodeOutput output = new ObjectCodeOutput(startAddress, originFill, false, false);
		output.write(this);
		return output.bytes.toByteArray();
	}

	/** Emission metadata comes from the same pass that writes the bytes, never a source scan. */
	public record ObjectCode(byte[] bytes, List<Emission> emissions) {
		public ObjectCode { bytes=bytes.clone(); emissions=List.copyOf(emissions); }
		@Override public byte[] bytes() { return bytes.clone(); }
	}
	public ObjectCode getObjectCode(int originFill) {
		return getObjectCode(originFill, false);
	}

	public ObjectCode getObjectCode(int originFill, boolean ignoreConsoleInstructions) {
		return getObjectCode(originFill, ignoreConsoleInstructions, null, null);
	}

	ObjectCode getObjectCode(int originFill, boolean ignoreConsoleInstructions,
			Consumer<AssemblyException> errors, Map<Line, byte[]> consoleReplacements) {
		ObjectCodeOutput output = new ObjectCodeOutput(startAddress, originFill, false, true,
				ignoreConsoleInstructions, consoleReplacements);
		output.errors = errors;
		output.write(this);
		output.finishConsoleString();
		return new ObjectCode(output.bytes.toByteArray(), output.emissions);
	}

	public static ObjectCode getSectionObjectCode(List<Source> sections, Expression address, int originFill) {
		return getSectionObjectCode(sections, address, originFill, false, null);
	}

	public static ObjectCode getSectionObjectCode(List<Source> sections, Expression address, int originFill,
			boolean ignoreConsoleInstructions, Map<Line, byte[]> consoleReplacements) {
		ObjectCodeOutput output = new ObjectCodeOutput(address, originFill, true, true,
				ignoreConsoleInstructions, consoleReplacements);
		for (Source section : sections) output.write(section);
		output.finishConsoleString();
		return new ObjectCode(output.bytes.toByteArray(), output.emissions);
	}

	/** Fixed-address DS sections share a cursor; a section's first ORG is not a new file base. */
	public static byte[] getSectionBytes(List<Source> sections, Expression address, int originFill) {
		ObjectCodeOutput output = new ObjectCodeOutput(address, originFill, true, false);
		for (Source section : sections)
			output.write(section);
		return output.bytes.toByteArray();
	}

	/**
	 * Physical output is independent of the logical counter restored by DEPHASE.
	 * Conditional bodies share this cursor, while DS sections have a bounded, separate one.
	 */
	private static final class ObjectCodeOutput {
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private final int originFill;
		private final boolean metadata;
		private final boolean ignoreConsoleInstructions;
		private final Map<Line, byte[]> consoleReplacements;
		private Line consoleString;
		private final List<Emission> emissions = new ArrayList<>();
		private Consumer<AssemblyException> errors;
		private long address;
		private boolean anchored;
		private int phaseDepth;

		ObjectCodeOutput(Expression address, int originFill, boolean anchored, boolean metadata) {
			this(address, originFill, anchored, metadata, false, null);
		}

		ObjectCodeOutput(Expression address, int originFill, boolean anchored, boolean metadata,
				boolean ignoreConsoleInstructions, Map<Line, byte[]> consoleReplacements) {
			if (originFill < 0 || originFill > 255)
				throw new IllegalArgumentException("ORG fill must be a byte (0..255).");
			this.address = address.getInteger();
			this.originFill = originFill;
			this.anchored = anchored;
			this.metadata = metadata;
			this.ignoreConsoleInstructions = ignoreConsoleInstructions;
			this.consoleReplacements = consoleReplacements;
		}

		void write(Source source) {
			for (Line line : source.lines) {
				try {
					write(line);
				} catch (AssemblyException error) {
					if (errors == null)
						throw error;
					errors.accept(error);
					// Resolution succeeded. Keep the physical cursor at the planned address
					// so a failed encoding does not affect checks on later statements.
					int size = line.getSize().getInteger();
					address += size;
					anchored |= size != 0;
				}
			}
		}

		private void write(Line line) {
			if (line.getInstructionObject() instanceof org.tbc.glass.instructions.Org.Org_N org) {
				try {
					if (phaseDepth == 0)
						origin(org.getOrigin().getInteger(),line);
				} catch (AssemblyException e) {
					e.addContext(line.getSourceSpan());
					throw e;
				}
			} else if (line.getInstructionObject() instanceof org.tbc.glass.instructions.If.IfObject conditional) {
				try {
					write(conditional.getSelectedSource());
				} catch (AssemblyException e) {
					e.addContext(line.getSourceSpan());
					throw e;
				}
			} else {
				if (line.getInstructionObject() instanceof org.tbc.glass.instructions.Phase.PhaseObject)
					phaseDepth++;
				else if (line.getInstructionObject() instanceof org.tbc.glass.instructions.Dephase.DephaseObject)
					phaseDepth--;

				byte[] object;
				if(metadata && line.getInstructionObject() instanceof org.tbc.glass.instructions.Ds.Ds_N_N ds) {
					try {
						int previousReplacements = consoleReplacements == null ? 0 : consoleReplacements.size();
						ObjectCode nested = ds.getObjectCode(originFill, line.getSourceSpan().toRange(),
								ignoreConsoleInstructions, consoleReplacements);
						object = nested.bytes();
						if (object.length > 0) finishConsoleString();
						if (consoleReplacements != null && consoleReplacements.size() > previousReplacements)
							consoleReplacements.put(line, object);
						for(Emission emission:nested.emissions()) emissions.add(emission.shifted(bytes.size()));
					} catch(AssemblyException error) { error.addContext(line.getSourceSpan()); throw error; }
				} else {
					object = line.getBytes(originFill);
					if (ignoreConsoleInstructions && object.length > 0)
						object = replaceConsoleBytes(line, object);
					if(metadata && object.length>0) emissions.add(new Emission(bytes.size(),object.length,
							line.getScope().getAddress().getInteger(),Emission.Kind.DATA,line.getSourceSpan().toRange()));
				}
				bytes.write(object, 0, object.length);
				address += object.length;
				anchored |= object.length != 0;
			}
		}

		private byte[] replaceConsoleBytes(Line line, byte[] object) {
			var instruction = line.getInstruction();
			int length = 0;
			if (consoleString != null) {
				if (instruction instanceof Db || instruction instanceof Dw || instruction instanceof Dd
						|| instruction instanceof MzAsc || instruction instanceof MzDisp || instruction instanceof Incbin) {
					while (length < object.length) {
						if (object[length++] == 0x0d) {
							consoleString = null;
							break;
						}
					}
				} else finishConsoleString();
			}
			if (instruction instanceof ConsoleStringOutput) {
				consoleString = line;
				length = object.length;
			} else if (instruction instanceof ConsoleRegisterInput || instruction instanceof ConsoleRegisterOutput
					|| instruction instanceof ConsoleMemoryInput || instruction instanceof ConsoleMemoryOutput
					|| instruction instanceof ConsoleStringInput) {
				length = object.length;
			}
			if (length == 0) return object;
			byte[] nops = length == object.length ? new byte[length] : object.clone();
			if (length != object.length) Arrays.fill(nops, 0, length, (byte) 0);
			if (consoleReplacements != null) consoleReplacements.put(line, nops);
			return nops;
		}

		void finishConsoleString() {
			if (consoleString == null) return;
			var error = new AssemblyException("C_SOUT requires inline data terminated by $0D before the next instruction or output gap.");
			error.addContext(consoleString.getSourceSpan());
			consoleString = null;
			if (errors == null) throw error;
			errors.accept(error);
		}

		private void origin(int target,Line line) {
			if (!anchored) {
				// A leading ORG establishes the base, rather than padding from zero.
				address = target;
				anchored = true;
				return;
			}
			long gap = (long)target - address;
			if (gap < 0)
				throw new AssemblyException("ORG overlaps earlier output (target: " + target
						+ ", output end: " + address + ").");
			if (gap > Integer.MAX_VALUE - bytes.size())
				throw new AssemblyException("ORG gap exceeds the supported object size.");
			if (gap > 0) finishConsoleString();
			byte[] padding = new byte[(int)gap];
			Arrays.fill(padding, (byte)originFill);
			if(metadata && padding.length>0) emissions.add(new Emission(bytes.size(),padding.length,address,
					Emission.Kind.ORIGIN_PADDING,line.getSourceSpan().toRange()));
			bytes.write(padding, 0, padding.length);
			address = target;
		}
	}

	public String toString() {
		StringBuilder string = new StringBuilder();
		for (Line line : lines) {
			string.append(line);
			string.append('\n');
		}
		return string.toString();
	}

	public static class MzfConfig {
		private String title = "TITLE";
		private String comments = "";
		private Integer loadAddress;
		private Integer startAddress;

		public String getTitle() {
			return title;
		}

		public String getComments() {
			return comments;
		}

		public boolean hasLoadAddress() {
			return loadAddress != null;
		}

		public int getLoadAddress() {
			return loadAddress;
		}

		public int getStartAddress() {
			return startAddress != null ? startAddress : loadAddress;
		}
	}

}
