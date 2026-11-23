package org.tbc.glass;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.tbc.glass.SourceFile.SourceFileSpan;
import org.tbc.glass.api.AssemblyRequest;
import org.tbc.glass.api.Diagnostic;
import org.tbc.glass.api.SourceRange;

public class Assembler {

    private final Source source;
	private final int originFill;
	private static final int MZF_HEADER_SIZE = 128;

	/**
     */
	static void main( String[] args ) {
		try {
			run(args);
		} catch (AssemblyException e) {
			System.err.println(formatError(e));
			System.exit(1);
		}
	}

	private static void run(String[] args) {
		if (args.length == 0) {
			System.out.printf( "%s %s by %s%n",
                               Assembler.class.getPackage().getImplementationTitle(),
                               Assembler.class.getPackage().getImplementationVersion(),
                               Assembler.class.getPackage().getImplementationVendor()
                             );
			System.out.println();
			System.out.println("Usage: java -jar glass.jar [OPTION] SOURCE [OBJECT] [SYMBOL]");
			System.out.println("  --all-errors  Report multiple independent assembly errors.");
			System.exit(1);
		}

		Path sourcePath = null;
		Path objectPath = null;
		Path symbolPath = null;
		Path listPath = null;
		Path debugPath = null;
		OutputType outputType = OutputType.BIN;
		int originFill = 0;
		boolean allErrors = false;
		List<Path> includePaths = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("-I")) {
				if (++i >= args.length)
					throw new AssemblyException("Missing argument value.");
				includePaths.add(Paths.get(args[i]));
			} else if (args[i].equals("-L")) {
				if (++i >= args.length)
					throw new AssemblyException("Missing argument value.");
				listPath = Paths.get(args[i]);
			} else if (args[i].equals("-D")) {
				if (++i >= args.length)
					throw new AssemblyException("Missing argument value.");
				debugPath = Paths.get(args[i]);
			} else if (args[i].equals("-F")) {
				if (++i >= args.length)
					throw new AssemblyException("Missing argument value.");
				originFill = parseOriginFill(args[i]);
			} else if (args[i].equals("--all-errors")) {
				allErrors = true;
			} else if (args[i].equals("-O")) {
				if (++i >= args.length)
					throw new AssemblyException("Missing argument value.");
				outputType = OutputType.parse(args[i]);
			} else if (sourcePath == null) {
				sourcePath = Paths.get(args[i]);
			} else if (objectPath == null) {
				objectPath = Paths.get(args[i]);
			} else if (symbolPath == null) {
				symbolPath = Paths.get(args[i]);
			} else {
				throw new AssemblyException("Too many arguments.");
			}
		}

		if (sourcePath == null)
			throw new AssemblyException("Missing source file.");
		if (outputType == OutputType.MZF)
			objectPath = withMzfExtension(objectPath);
		Assembler instance;
		if (allErrors) {
			var assembled = assembleCollecting(new AssemblyRequest(sourcePath, includePaths, Map.of()),
					SourceProvider.fileSystem(), originFill, null, error -> System.err.println(formatError(error)));
			if (assembled == null) {
				System.exit(1);
				return;
			}
			instance = new Assembler(assembled.source(), originFill);
			writeObjectBytes(objectPath, encodeObject(assembled.source(), assembled.object().bytes(), outputType));
		} else {
			instance = new Assembler(sourcePath, includePaths, originFill);
			instance.writeObject(objectPath, outputType);
		}
		if (debugPath != null)
			instance.writeDebug( debugPath );
		if (symbolPath != null)
			instance.writeSymbols( symbolPath );
		if (listPath != null)
			instance.writeList( listPath );
	}

	private static String formatError(AssemblyException e) {
		SourceFileSpan context = !e.getContexts().isEmpty() ? e.getContexts().get(0) : null;
		String path = context != null && context.getSourceFile().getPath() != null ?
			context.getSourceFile().getPath().toAbsolutePath().normalize().toString() : "<unknown>";
		int line = context != null ? context.lineStart + 1 : 1;
		int column = context != null && context.column != -1 ? context.column + 1 : 1;
		return path + ":" + line + ":" + column + ": error: " + e.getPlainMessage();
	}

	public Assembler(Path sourcePath, List<Path> includePaths) {
		this(sourcePath, includePaths, 0);
	}

	/** Selects the byte used only for forward ORG gaps (for example, 0xFF for ROM). */
	public Assembler(Path sourcePath, List<Path> includePaths, int originFill) {
		if (originFill < 0 || originFill > 255)
			throw new IllegalArgumentException("ORG fill must be a byte (0..255).");
		this.originFill = originFill;
		this.source = new SourceBuilder(includePaths).parse(sourcePath);
	}

	private Assembler(Source source, int originFill) {
		this.originFill = originFill;
		this.source = source;
	}

	public record AssembledSource(Source source, Source.ObjectCode object) {}

	/** Shared CLI and API recovery. Returns null if any assembly error was reported. */
	public static AssembledSource assembleCollecting(AssemblyRequest request, SourceProvider fallback, int originFill,
			Consumer<Diagnostic> warnings, Consumer<AssemblyException> report) {
		record ErrorLocation(SourceRange range, String message) {}
		Set<ErrorLocation> seen = new HashSet<>();
		Consumer<AssemblyException> reportOnce = error -> {
			var diagnostic = Diagnostic.fromException(error, request.sourcePath());
			if (seen.add(new ErrorLocation(diagnostic.range(), diagnostic.message())))
				report.accept(error);
		};
		var inputs = SourceProvider.overlay(request.sourceOverrides(), fallback);
		var builder = SourceBuilder.forDiagnostics(request.includePaths(), inputs, warnings,
				request.ignoreMzfDirectives(), reportOnce);
		Source source = null;
		boolean registeredCleanly = false;
		try {
			source = builder.parse(request.sourcePath());
			source.register();
			registeredCleanly = seen.isEmpty();
			if (registeredCleanly) {
				source.expand();
				source.resolve();
			}
		} catch (AssemblyException error) {
			reportOnce.accept(error);
		}
		if (!seen.isEmpty()) {
			builder.validateRecoveredOperands(registeredCleanly);
			return null;
		}
		var object = source.getObjectCode(originFill, reportOnce);
		return seen.isEmpty() ? new AssembledSource(source, object) : null;
	}

	private static int parseOriginFill(String text) {
		try {
			int value = text.startsWith("$") ? Integer.parseInt(text.substring(1), 16)
					: text.startsWith("0x") || text.startsWith("0X") ? Integer.parseInt(text.substring(2), 16)
					: Integer.parseInt(text);
			if (value >= 0 && value <= 255)
				return value;
		} catch (NumberFormatException e) {
			// Report malformed/out-of-range command-line settings consistently.
		}
		throw new AssemblyException("ORG fill must be a byte (0..255): " + text);
	}

	public void writeObject(Path objectPath) {
		writeObject(objectPath, OutputType.BIN);
	}

	public void writeObject(Path objectPath, OutputType outputType) {
		try (OutputStream output = objectPath != null ? createBufferedOutputStream(objectPath) : new NullOutputStream()) {
			byte[] object = encodeObject(source, assemble(), outputType);
			output.write(object, 0, object.length);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void writeObjectBytes(Path objectPath, byte[] bytes) {
		if (objectPath == null) return;
		try (OutputStream output = createBufferedOutputStream(objectPath)) {
			output.write(bytes);
		} catch (IOException error) {
			throw new RuntimeException(error);
		}
	}

	private byte[] assemble() throws IOException {
		try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			source.assemble(output, originFill);
			return output.toByteArray();
		}
	}

	/** Shared object encoding for command-line and in-process builds. Does not write files. */
	public static byte[] encodeObject(Source source, byte[] object, OutputType outputType) {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(outputType, "outputType");
		return outputType == OutputType.MZF ? createMzfObject(source, object) : object.clone();
	}

	private static byte[] createMzfObject(Source source, byte[] object) {
		Source.MzfConfig mzfConfig = source.getMzfConfig();
		if (!mzfConfig.hasLoadAddress())
			throw new AssemblyException("MZF_LOAD is required for MZF output.");
		return encodeMzfObject(object,mzfConfig.getTitle(),mzfConfig.getComments(),mzfConfig.getLoadAddress(),mzfConfig.getStartAddress());
	}

	/** Shared MZF encoder; IDE callers supply project-owned metadata without injecting source. */
	public static byte[] encodeMzfObject(byte[] object, String title, String comments, int loadAddress, int runAddress) {

		int payloadSize = object.length;
		checkWord("MZF size", payloadSize);
		checkWord("MZF_LOAD", loadAddress);
		checkWord("MZF_START", runAddress);

		byte[] mzfObject = new byte[MZF_HEADER_SIZE + payloadSize];
		mzfObject[0] = 1;
		writeTextField(mzfObject, 1, 17, title, true);
		writeWord(mzfObject, 18, payloadSize);
		writeWord(mzfObject, 20, loadAddress);
		writeWord(mzfObject, 22, runAddress);
		writeTextField(mzfObject, 24, 104, comments, false);
		System.arraycopy(object, 0, mzfObject, MZF_HEADER_SIZE, object.length);
		return mzfObject;
	}

	private static void writeTextField(byte[] output, int offset, int length, String text, boolean terminate) {
		int maxTextLength = terminate ? length - 1 : length;
		int copyLength = Math.min(text.length(), maxTextLength);
		for (int i = 0; i < copyLength; i++)
			output[offset + i] = Mz700Charset.encode(text.charAt(i));
		if (terminate)
			output[offset + copyLength] = 0x0D;
	}

	private static void writeWord(byte[] output, int offset, int value) {
		output[offset] = (byte)value;
		output[offset + 1] = (byte)(value >> 8);
	}

	private static void checkWord(String field, int value) {
		if (value < 0 || value > 0xFFFF)
			throw new AssemblyException(field + " out of range: " + value);
	}

	public void writeSymbols(Path symbolPath) {
		try (PrintStream symbolOutput = new PrintStream(createBufferedOutputStream(symbolPath))) {
			symbolOutput.print(source.getScope().serializeSymbols());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public void writeList(Path listPath) {
		try (PrintStream output = new PrintStream(createBufferedOutputStream(listPath))) {
			new ListingWriter(output).write(source);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public void writeDebug(Path debugPath) {
		try (PrintStream output = new PrintStream(createBufferedOutputStream(debugPath))) {
			new DebugWriter(output).write(source);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static OutputStream createBufferedOutputStream(Path path) throws IOException {
		return new BufferedOutputStream(Files.newOutputStream(path), 0x10000);
	}

	private static Path withMzfExtension(Path path) {
		if (path == null)
			return null;
		String fileName = path.getFileName().toString();
		int extensionIndex = fileName.lastIndexOf('.');
		String mzfFileName = (extensionIndex != -1 ? fileName.substring(0, extensionIndex) : fileName) + ".mzf";
		Path parent = path.getParent();
		return parent != null ? parent.resolve(mzfFileName) : Paths.get(mzfFileName);
	}

	public static class NullOutputStream extends OutputStream {
		public void write(int b) throws IOException {}
		public void write(byte[] b) throws IOException {}
		public void write(byte[] b, int off, int len) throws IOException {}
	}

	public enum OutputType {
		BIN,
		MZF;

		public static OutputType parse(String value) {
			if ("BIN".equals(value))
				return BIN;
			if ("MZF".equals(value))
				return MZF;
			throw new AssemblyException("Unsupported output type: " + value);
		}
	}

}
