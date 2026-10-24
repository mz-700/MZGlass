package org.tbc.glass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.tbc.glass.api.SourceRange;

public class SourceFile {

	private final Path path;
	private final List<String> content;
	private final String text;
	private final int[] lineOffsets;
	private final int readerLineCount;

	public SourceFile(Path path) {
		this(path, readText(path));
	}

	private static String readText(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssemblyException("Cannot read source file: " + path, e);
		}
	}

	public SourceFile(String string) {
		this(null, string);
	}

	/** Source snapshot retaining its original path, including files not yet on disk. */
	public SourceFile(Path path, String string) {
		this.path = path;
		this.text = string.replace("\r\n", "\n").replace('\r', '\n');
		this.content = Arrays.asList(text.split("\n", -1));
		// Named snapshots follow Files.readAllLines' EOF behavior, preserving CLI listings.
		this.readerLineCount = path == null ? content.size() : text.isEmpty() ? 0
				: content.size() - (text.endsWith("\n") ? 1 : 0);
		this.lineOffsets = new int[content.size()];
		for (int line = 1; line < lineOffsets.length; line++)
			lineOffsets[line] = lineOffsets[line - 1] + content.get(line - 1).length() + 1;
	}

	/** Text and offsets use LF line endings and UTF-16 code units. */
	public String getText() { return text; }

	public int getLineCount() { return content.size(); }

	public int getLineStartOffset(int line) {
		if (line < 0 || line > content.size()) throw new IndexOutOfBoundsException(line);
		return line == content.size() ? text.length() : lineOffsets[line];
	}

	public SourceFileReader getReader() {
		return new SourceFileReader();
	}

	public Path getPath() {
		return path;
	}

	public String getLine(int lineNumber) {
		return content.get(lineNumber);
	}

	public class SourceFileReader {

		int lineNumber = 0;

		private SourceFileReader() {
		}

		public SourceFile getSourceFile() {
			return SourceFile.this;
		}

		public String readLine() {
			if (lineNumber >= readerLineCount)
				return null;
			return getLine(lineNumber++);
		}

		public SourceFileSpan getSpan(SourceFileSpan span) {
			return new SourceFileSpan(span != null ? span.lineStart : lineNumber,
				lineNumber >= readerLineCount ? lineNumber : lineNumber + 1);
		}
	}

	public class SourceFileSpan {

		final int lineStart;
		final int lineEnd;
		final int column;

		public SourceFileSpan(int lineStart, int lineEnd, int column) {
			this.lineStart = lineStart;
			this.lineEnd = lineEnd;
			this.column = column;
		}

		public SourceFileSpan(int lineStart, int lineEnd) {
			this(lineStart, lineEnd, -1);
		}

		public SourceFile getSourceFile() {
			return SourceFile.this;
		}

		/** Zero-based first line and exclusive last line of the logical statement. */
		public int getLineStart() { return lineStart; }
		public int getLineEnd() { return lineEnd; }

		/** Zero-based UTF-16 column on the last line, or -1 for a statement-wide span. */
		public int getColumn() { return column; }

		public SourceRange toRange() {
			int first = Math.clamp(lineStart, 0, content.size());
			if (first == content.size()) return new SourceRange(path, text.length(), text.length());
			int last = Math.clamp(lineEnd - 1, first, content.size() - 1);
			if (column >= 0) {
				int start = lineOffsets[last] + Math.min(column, content.get(last).length());
				int length = start < text.length() && text.charAt(start) != '\n' ? Character.charCount(text.codePointAt(start)) : 0;
				return new SourceRange(path, start, start + length);
			}
			return new SourceRange(path, lineOffsets[first], lineOffsets[last] + content.get(last).length());
		}

		public SourceFileSpan atColumn(int column) {
			return new SourceFileSpan(lineStart, lineEnd, column);
		}

		@Override
		public String toString() {
			StringBuilder string = new StringBuilder( "[at " + getSourceFile().getPath() + ":" + ( lineStart + 1 ) + ( column != -1 ? "," + ( column + 1 ) : "" ) + "]" );

			for (int i = lineStart; i < lineEnd; i++) {
				string.append( "\n" )
                      .append( getLine( i ) );
			}

			if (column != -1) {
				String line = getLine(Math.min(lineEnd, content.size()) - 1);
				int end = Math.min(column, line.length());
				string.append( "\n" )
                      .append( line.substring( 0, end )
                                   .replaceAll( "[^\t]", " " ) )
                      .append( "^" );
			}

			return string.toString();
		}
	}
}
