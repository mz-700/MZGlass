package org.tbc.glass;

import java.util.function.Consumer;

import org.tbc.glass.SourceFile.SourceFileReader;
import org.tbc.glass.SourceFile.SourceFileSpan;
import org.tbc.glass.expressions.CharacterLiteral;
import org.tbc.glass.expressions.ContextLiteral;
import org.tbc.glass.expressions.ExpressionBuilder;
import org.tbc.glass.expressions.Identifier;
import org.tbc.glass.expressions.IntegerLiteral;
import org.tbc.glass.expressions.StringLiteral;

import java.io.Serial;

public class Parser {

	private final SourceFileReader reader;
	private boolean ignoreMzfDirectives;
	private Scope scope;
	private Scope lineScope;
	private DotLocalLabels dotLabels;
	private int currentColumn;
	private LineBuilder lineBuilder = new LineBuilder();

	private State state;
	private final StringBuilder accumulator = new StringBuilder();
	private ExpressionBuilder expressionBuilder = new ExpressionBuilder();

	public Parser(SourceFile sourceFile) {
		this(sourceFile, false);
	}

	/** IDE policy only: discard directive arguments before trying to parse them. */
	public Parser(SourceFile sourceFile, boolean ignoreMzfDirectives) {
		this.ignoreMzfDirectives = ignoreMzfDirectives;
		reader = sourceFile.getReader();
		state = labelStartState;
	}

	public SourceFile getSourceFile() {
		return reader.getSourceFile();
	}

	void ignoreMzfDirectives(boolean ignore) { this.ignoreMzfDirectives = ignore; }

	public Line parse(Scope scope) {
		return parse(scope, null);
	}

	Line parse(Scope scope, DotLocalLabels dotLabels) {
		this.scope = scope;
		this.dotLabels = dotLabels;
		this.lineScope = new Scope(scope);

		SourceFileSpan span = null;
		try {
			do {
				span = reader.getSpan(span);
				String sourceLine = reader.readLine();
				if (sourceLine == null) {
					state = state.parse('\0');
					if (state != labelStartState)
						throw new AssemblyException("Unexpected end of file.");
					if (lineBuilder.isEmpty())
						lineBuilder.setMnemonic("END");  // otherwise return END the next time
					break;
				}
				currentColumn = 0;

				for (int i = 0, length = sourceLine.length(); i < length; i++) {
					currentColumn = i;
					state = state.parse(sourceLine.charAt(i));
				}
				currentColumn = sourceLine.length();
				state = state.parse('\n');
				if (state == labelStartState && lineBuilder.isEmpty())
					span = null;
			} while (state != labelStartState || lineBuilder.isEmpty());

			if ( !accumulator.isEmpty() )
				throw new AssemblyException( "Accumulator not consumed. Value: " + accumulator );
		} catch(AssemblyException e) {
			e.addContext(span.atColumn(currentColumn));
			throw e;
		}

		return lineBuilder.getLine(lineScope, span);
	}

	/** The reader has consumed the physical line that failed. Start fresh on the next one. */
	Line parseRecovering(Scope scope, DotLocalLabels dotLabels, Consumer<AssemblyException> report) {
		while (true) {
			try {
				return parse(scope, dotLabels);
			} catch (AssemblyException error) {
				report.accept(error);
				state = labelStartState;
				lineBuilder = new LineBuilder();
				expressionBuilder = new ExpressionBuilder();
				accumulator.setLength(0);
			}
		}
	}

	private void setLabel(String name) {
		lineBuilder.setLabel(dotLabels == null ? name : dotLabels.declare(name));
	}

	private String qualify(String name) {
		return DotLocalLabels.qualify(dotLabels == null ? null : dotLabels.owner(), name);
	}

	void skipToArgumentStartState(String mnemonic)
	{
		lineBuilder.setMnemonic(mnemonic);
		state = argumentStartState;
	}

	private abstract static class State {
		public abstract State parse(char character);

		public boolean isWhitespace(char character) {
			return character == ' ' || character == '\t';
		}

		public boolean isIdentifier(char character) {
			return isIdentifierStart(character) || character >= '0' && character <= '9' ||
					character == '\'' || character == '$';
		}

		public boolean isIdentifierStart(char character) {
			return character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z' ||
					character == '_' || character == '.' || character == '?' || character == '@';
		}

		public boolean isStandaloneMnemonic(String identifier) {
			return "end".equals(identifier) || "END".equals(identifier) ||
					"endm".equals(identifier) || "ENDM".equals(identifier) ||
					"endp".equals(identifier) || "ENDP".equals(identifier) ||
					"ends".equals(identifier) || "ENDS".equals(identifier) ||
					"endstruct".equals(identifier) || "ENDSTRUCT".equals(identifier) ||
					"endrel".equals(identifier) || "ENDREL".equals(identifier) ||
					"else".equals(identifier) || "ELSE".equals(identifier) ||
					"endif".equals(identifier) || "ENDIF".equals(identifier);
		}

	}

	private final LabelStartState labelStartState = new LabelStartState();
	private class LabelStartState extends State {
		public State parse(char character) {
			if (isIdentifierStart(character)) {
				accumulator.append(character);
				return labelReadState;
			} else if (isWhitespace(character)) {
				return statementStartState;
			} else if (character == ';') {
				return commentReadState;
			} else if (character == '\n' || character == '\0') {
				return labelStartState;
			}
			throw new SyntaxError();
		}
	}

	private final LabelReadState labelReadState = new LabelReadState();
	private class LabelReadState extends State {
		public State parse(char character) {
			if (isIdentifier(character)) {
				accumulator.append(character);
				return labelReadState;
			} else {
				String identifier = accumulator.toString();
				accumulator.setLength(0);
				if (character != ':' && isStandaloneMnemonic(identifier)) {
					lineBuilder.setMnemonic(identifier, currentColumn - identifier.length());
					if (isWhitespace(character)) {
						return argumentStartState;
					} else if (character == ';') {
						return commentReadState;
					} else if (character == '\n' || character == '\0') {
						return labelStartState;
					}
				} else if (character == ':' || isWhitespace(character)) {
					setLabel(identifier);
					return statementStartState;
				} else if (character == ';') {
					setLabel(identifier);
					return commentReadState;
				} else if (character == '\n' || character == '\0') {
					setLabel(identifier);
					return labelStartState;
				}
			}
			throw new SyntaxError();
		}
	}

	private final StatementStartState statementStartState = new StatementStartState();
	private class StatementStartState extends State {
		public State parse(char character) {
			if (isIdentifierStart(character)) {
				accumulator.append(character);
				return statementReadState;
			} else if (isWhitespace(character)) {
				return statementStartState;
			} else if (character == ';') {
				return commentReadState;
			} else if (character == '\n' || character == '\0') {
				return labelStartState;
			}
			throw new SyntaxError();
		}
	}

	private final StatementReadState statementReadState = new StatementReadState();
	private class StatementReadState extends State {
		public State parse(char character) {
			if (isIdentifier(character)) {
				accumulator.append(character);
				return statementReadState;
			} if (character == ':') {
				setLabel(accumulator.toString());
				accumulator.setLength(0);
				return statementStartState;
			} else {
				String mnemonic = accumulator.toString();
				if (ignoreMzfDirectives && mnemonic.regionMatches(true, 0, "mzf_", 0, 4)) {
					lineBuilder.setMnemonic(mnemonic, currentColumn - mnemonic.length());
					lineBuilder.ignoreInstruction();
					accumulator.setLength(0);
					return commentReadState.parse(character);
				}
				lineBuilder.setMnemonic(qualify(mnemonic), currentColumn - mnemonic.length());
				accumulator.setLength(0);
				if (isWhitespace(character)) {
					return isRawArgumentMnemonic(mnemonic) ? rawArgumentStartState : argumentStartState;
				} else if (character == ';') {
					return commentReadState;
				} else if (character == '\n' || character == '\0') {
					return labelStartState;
				}
			}
			throw new SyntaxError();
		}
	}

	private boolean isRawArgumentMnemonic(String mnemonic) {
		return "mzf_title".equals(mnemonic) || "MZF_TITLE".equals(mnemonic) ||
				"mzf_comments".equals(mnemonic) || "MZF_COMMENTS".equals(mnemonic);
	}

	private final RawArgumentStartState rawArgumentStartState = new RawArgumentStartState();
	private class RawArgumentStartState extends State {
		public State parse(char character) {
			if (isWhitespace(character)) {
				return rawArgumentStartState;
			} else {
				return rawArgumentReadState.parse(character);
			}
		}
	}

	private final RawArgumentReadState rawArgumentReadState = new RawArgumentReadState();
	private class RawArgumentReadState extends State {
		public State parse(char character) {
			if (character == ';') {
				lineBuilder.setArguments(new StringLiteral(accumulator.toString().trim()));
				accumulator.setLength(0);
				return commentReadState;
			} else if (character == '\n' || character == '\0') {
				lineBuilder.setArguments(new StringLiteral(accumulator.toString().trim()));
				accumulator.setLength(0);
				return labelStartState;
			} else {
				accumulator.append(character);
				return rawArgumentReadState;
			}
		}
	}

	private final ArgumentStartState argumentStartState = new ArgumentStartState();
	private class ArgumentStartState extends State {
		public State parse(char character) {
			if (character == ';') {
				return commentReadState;
			} else if (character == '\n' || character == '\0') {
				return labelStartState;
			} else if (isWhitespace(character)) {
				return argumentStartState;
			} else {
				return argumentValueState.parse(character);
			}
		}
	}

	private final ArgumentValueState argumentValueState = new ArgumentValueState();
	private class ArgumentValueState extends State {
		public State parse(char character) {
			if (isIdentifierStart(character)) {
				accumulator.append(character);
				return argumentIdentifierState;
			} else if (character == '0') {
				accumulator.append(character);
				return argumentZeroState;
			} else if (character >= '1' && character <= '9') {
				accumulator.append(character);
				return argumentNumberState;
			} else if (character == '#') {
				return argumentHexadecimalState;
			} else if (character == '$') {
				return argumentDollarState;
			} else if (character == '%') {
				return argumentBinaryState;
			} else if (character == '"') {
				return argumentStringState;
			} else if (character == '\'') {
				return argumentCharacterState;
			} else if (character == '+') {
				expressionBuilder.addOperatorToken(expressionBuilder.POSITIVE);
				return argumentValueState;
			} else if (character == '-') {
				expressionBuilder.addOperatorToken(expressionBuilder.NEGATIVE);
				return argumentValueState;
			} else if (character == '~') {
				expressionBuilder.addOperatorToken(expressionBuilder.COMPLEMENT);
				return argumentValueState;
			} else if (character == '!') {
				expressionBuilder.addOperatorToken(expressionBuilder.NOT);
				return argumentValueState;
			} else if (character == '(') {
				expressionBuilder.addOperatorToken(expressionBuilder.GROUP_OPEN);
				return argumentValueState;
			} else if (isWhitespace(character)) {
				return argumentValueState;
			} else if (character == ';') {
				return commentReadThenArgumentState;
			} else if (character == '\n') {
				return argumentValueState;
			}
			throw new SyntaxError();
		}
	}

	private final ArgumentIdentifierState argumentIdentifierState = new ArgumentIdentifierState();
	private class ArgumentIdentifierState extends State {
		public State parse(char character) {
			if (isIdentifier(character)) {
				accumulator.append(character);
				return argumentIdentifierState;
			} else {
				expressionBuilder.addValueToken(new Identifier(qualify(accumulator.toString()), scope));
				accumulator.setLength(0);
				return argumentOperatorState.parse(character);
			}
		}
	}

	private final ArgumentStringState argumentStringState = new ArgumentStringState();
	private class ArgumentStringState extends State {
		public State parse(char character) {
			if (character == '"') {
				return argumentStringDoubleQuoteState;
			} else if (character == '\\') {
				return argumentStringEscapeState;
			} else if (character == '\n' || character == '\0') {
				throw new SyntaxError();
			} else {
				accumulator.append(character);
				return argumentStringState;
			}
		}
	}

	private final ArgumentStringDoubleQuoteState argumentStringDoubleQuoteState = new ArgumentStringDoubleQuoteState();
	private class ArgumentStringDoubleQuoteState extends State {
		public State parse(char character) {
			if (character == '"') {
				accumulator.append(character);
				return argumentStringState;
			} else {
				expressionBuilder.addValueToken(new StringLiteral(accumulator.toString()));
				accumulator.setLength(0);
				return argumentOperatorState.parse(character);
			}
		}
	}

	private final ArgumentStringEscapeState argumentStringEscapeState = new ArgumentStringEscapeState();
	private class ArgumentStringEscapeState extends State {
		public State parse(char character) {
			if (character == '0') {
				accumulator.append('\0');
				return argumentStringState;
			} else if (character == 'a') {
				accumulator.append('\7');
				return argumentStringState;
			} else if (character == 't') {
				accumulator.append('\t');
				return argumentStringState;
			} else if (character == 'n') {
				accumulator.append('\n');
				return argumentStringState;
			} else if (character == 'f') {
				accumulator.append('\f');
				return argumentStringState;
			} else if (character == 'r') {
				accumulator.append('\r');
				return argumentStringState;
			} else if (character == 'e') {
				accumulator.append('\33');
				return argumentStringState;
			} else if (character == '"') {
				accumulator.append('"');
				return argumentStringState;
			} else if (character == '\'') {
				accumulator.append('\'');
				return argumentStringState;
			} else if (character == '\\') {
				accumulator.append('\\');
				return argumentStringState;
			} else if (character == '\n' || character == '\0') {
				throw new SyntaxError();
			} else {
				throw new SyntaxError();
			}
		}
	}

	private final ArgumentCharacterState argumentCharacterState = new ArgumentCharacterState();
	private class ArgumentCharacterState extends State {
		public State parse(char character) {
			if (character == '\'') {
				return argumentCharacterDoubleQuoteState;
			} else if (character == '\\') {
				return argumentCharacterEscapeState;
			} else if (character == '\n' || character == '\0') {
				throw new SyntaxError();
			} else {
				accumulator.append(character);
				return argumentCharacterEndState;
			}
		}
	}

	private final ArgumentCharacterDoubleQuoteState argumentCharacterDoubleQuoteState = new ArgumentCharacterDoubleQuoteState();
	private class ArgumentCharacterDoubleQuoteState extends State {
		public State parse(char character) {
			if (character == '\'') {
				accumulator.append(character);
				return argumentCharacterEndState;
			} else {
				throw new SyntaxError();
			}
		}
	}

	private final ArgumentCharacterEscapeState argumentCharacterEscapeState = new ArgumentCharacterEscapeState();
	private class ArgumentCharacterEscapeState extends State {
		public State parse(char character) {
			State state = argumentStringEscapeState.parse(character);
			if (state == argumentStringState)
				return argumentCharacterEndState;
			throw new AssemblyException("Unexpected state.");
		}
	}

	private final ArgumentCharacterEndState argumentCharacterEndState = new ArgumentCharacterEndState();
	private class ArgumentCharacterEndState extends State {
		public State parse(char character) {
			if (character == '\'') {
				expressionBuilder.addValueToken(new CharacterLiteral(accumulator.charAt(0)));
				accumulator.setLength(0);
				return argumentOperatorState;
			} else {
				throw new SyntaxError();
			}
		}
	}

	private final ArgumentZeroState argumentZeroState = new ArgumentZeroState();
	private class ArgumentZeroState extends State {
		public State parse(char character) {
			if (character == 'x' || character == 'X') {
				accumulator.setLength(0);
				return argumentHexadecimalState;
			} else {
				return argumentNumberState.parse(character);
			}
		}
	}

	private final ArgumentNumberState argumentNumberState = new ArgumentNumberState();
	private class ArgumentNumberState extends State {
		public State parse(char character) {
			if (character >= '0' && character <= '9' || character >= 'A' && character <= 'F' ||
					character >= 'a' && character <= 'f') {
				accumulator.append(character);
				return argumentNumberState;
			} else {
				String string = accumulator.toString();
				if (character == 'H' || character == 'h') {
					int value = parseInt(string, 16);
					expressionBuilder.addValueToken(IntegerLiteral.of(value));
					accumulator.setLength(0);
					return argumentOperatorState;
				} else if (character == 'O' || character == 'o') {
					int value = parseInt(string, 8);
					expressionBuilder.addValueToken(IntegerLiteral.of(value));
					accumulator.setLength(0);
					return argumentOperatorState;
				} else {
					if (string.endsWith("B") || string.endsWith("b")) {
						int value = parseInt(string.substring(0, string.length() - 1), 2);
						expressionBuilder.addValueToken(IntegerLiteral.of(value));
						accumulator.setLength(0);
					} else {
						int value = parseInt(string, 10);
						expressionBuilder.addValueToken(IntegerLiteral.of(value));
						accumulator.setLength(0);
					}
					return argumentOperatorState.parse(character);
				}
			}
		}
	}

	private final ArgumentDollarState argumentDollarState = new ArgumentDollarState();
	private class ArgumentDollarState extends State {
		public State parse(char character) {
			if (character >= '0' && character <= '9' || character >= 'A' && character <= 'F' ||
					character >= 'a' && character <= 'f') {
				accumulator.append(character);
				return argumentHexadecimalState;
			} else {
				expressionBuilder.addValueToken(new ContextLiteral(lineScope));
				accumulator.setLength(0);
				return argumentOperatorState.parse(character);
			}
		}
	}

	private final ArgumentHexadecimalState argumentHexadecimalState = new ArgumentHexadecimalState();
	private class ArgumentHexadecimalState extends State {
		public State parse(char character) {
			if (character >= '0' && character <= '9' || character >= 'A' && character <= 'F' ||
					character >= 'a' && character <= 'f') {
				accumulator.append(character);
				return argumentHexadecimalState;
			} else {
				int value = parseInt(accumulator.toString(), 16);
				expressionBuilder.addValueToken(IntegerLiteral.of(value));
				accumulator.setLength(0);
				return argumentOperatorState.parse(character);
			}
		}
	}

	private final ArgumentBinaryState argumentBinaryState = new ArgumentBinaryState();
	private class ArgumentBinaryState extends State {
		public State parse(char character) {
			if (character >= '0' && character <= '1') {
				accumulator.append(character);
				return argumentBinaryState;
			} else {
				int value = parseInt(accumulator.toString(), 2);
				expressionBuilder.addValueToken(IntegerLiteral.of(value));
				accumulator.setLength(0);
				return argumentOperatorState.parse(character);
			}
		}
	}

	private final ArgumentOperatorState argumentOperatorState = new ArgumentOperatorState();
	private class ArgumentOperatorState extends State {
		public State parse(char character) {
			State state = tryParse(character);
			if (state != null) {
				return state;
			} else {
				throw new SyntaxError();
			}
		}

		public State tryParse(char character) {
			if (character == ')') {
				expressionBuilder.addOperatorToken(expressionBuilder.GROUP_CLOSE);
				return argumentOperatorState;
			} else if (character == '[') {
				expressionBuilder.addOperatorToken(expressionBuilder.INDEX_OPEN);
				return argumentValueState;
			} else if (character == ']') {
				expressionBuilder.addOperatorToken(expressionBuilder.INDEX_CLOSE);
				return argumentOperatorState;
			} else if (character == '.') {
				expressionBuilder.addOperatorToken(expressionBuilder.MEMBER);
				return argumentValueState;
			} else if (character == '*') {
				expressionBuilder.addOperatorToken(expressionBuilder.MULTIPLY);
				return argumentValueState;
			} else if (character == '/') {
				expressionBuilder.addOperatorToken(expressionBuilder.DIVIDE);
				return argumentValueState;
			} else if (character == '%') {
				expressionBuilder.addOperatorToken(expressionBuilder.MODULO);
				return argumentValueState;
			} else if (character == '+') {
				expressionBuilder.addOperatorToken(expressionBuilder.ADD);
				return argumentValueState;
			} else if (character == '-') {
				expressionBuilder.addOperatorToken(expressionBuilder.SUBTRACT);
				return argumentValueState;
			} else if (character == '<') {
				return argumentLessThanState;
			} else if (character == '>') {
				return argumentGreaterThanState;
			} else if (character == '=') {
				expressionBuilder.addOperatorToken(expressionBuilder.EQUALS);
				return argumentValueState;
			} else if (character == '!') {
				return argumentNotEqualsState;
			} else if (character == '&') {
				return argumentAndState;
			} else if (character == '^') {
				expressionBuilder.addOperatorToken(expressionBuilder.XOR);
				return argumentValueState;
			} else if (character == '|') {
				return argumentOrState;
			} else if (character == '?') {
				expressionBuilder.addOperatorToken(expressionBuilder.TERNARYIF);
				return argumentValueState;
			} else if (character == ':') {
				expressionBuilder.addOperatorToken(expressionBuilder.TERNARYELSE);
				return argumentValueState;
			} else if (character == ',') {
				expressionBuilder.addOperatorToken(expressionBuilder.SEQUENCE);
				return argumentValueState;
			} else if (isWhitespace(character)) {
				return argumentOperatorAnnotationState;
			} else if (character == ';') {
				return commentReadThenOperatorState;
			} else if (character == '\n' || character == '\0') {
				if (!expressionBuilder.hasOpenGroup() || character == '\0') {
					lineBuilder.setArguments(expressionBuilder.getExpression());
					return labelStartState;
				} else {
					return argumentOperatorAnnotationState;
				}
			} else {
				return null;
			}
		}
	}

	private final ArgumentOperatorAnnotationState argumentOperatorAnnotationState = new ArgumentOperatorAnnotationState();
	private class ArgumentOperatorAnnotationState extends State {
		public State parse(char character) {
			if (character == '!') {
				return argumentNotEqualsAnnotationState;
			} else {
				State state = argumentOperatorState.tryParse(character);
				if (state != null) {
					return state;
				} else {
					expressionBuilder.addOperatorToken(expressionBuilder.ANNOTATION);
					return argumentValueState.parse(character);
				}
			}
		}
	}

	private final ArgumentLessThanState argumentLessThanState = new ArgumentLessThanState();
	private class ArgumentLessThanState extends State {
		public State parse(char character) {
			if (character == '<') {
				expressionBuilder.addOperatorToken(expressionBuilder.SHIFT_LEFT);
				return argumentValueState;
			} else if (character == '=') {
				expressionBuilder.addOperatorToken(expressionBuilder.LESS_OR_EQUALS);
				return argumentValueState;
			} else {
				expressionBuilder.addOperatorToken(expressionBuilder.LESS_THAN);
				return argumentValueState.parse(character);
			}
		}
	}

	private final ArgumentGreaterThanState argumentGreaterThanState = new ArgumentGreaterThanState();
	private class ArgumentGreaterThanState extends State {
		public State parse(char character) {
			if (character == '>') {
				return argumentShiftRightState;
			} else if (character == '=') {
				expressionBuilder.addOperatorToken(expressionBuilder.GREATER_OR_EQUALS);
				return argumentValueState;
			} else {
				expressionBuilder.addOperatorToken(expressionBuilder.GREATER_THAN);
				return argumentValueState.parse(character);
			}
		}
	}

	private final ArgumentShiftRightState argumentShiftRightState = new ArgumentShiftRightState();
	private class ArgumentShiftRightState extends State {
		public State parse(char character) {
			if (character == '>') {
				expressionBuilder.addOperatorToken(expressionBuilder.SHIFT_RIGHT_UNSIGNED);
				return argumentValueState;
			} else {
				expressionBuilder.addOperatorToken(expressionBuilder.SHIFT_RIGHT);
				return argumentValueState.parse(character);
			}
		}
	}

	private final ArgumentNotEqualsState argumentNotEqualsState = new ArgumentNotEqualsState();
	private class ArgumentNotEqualsState extends State {
		public State parse(char character) {
			if (character == '=') {
				expressionBuilder.addOperatorToken(expressionBuilder.NOT_EQUALS);
				return argumentValueState;
			} else {
				throw new SyntaxError();
			}
		}
	}

	private final ArgumentNotEqualsAnnotationState argumentNotEqualsAnnotationState = new ArgumentNotEqualsAnnotationState();
	private class ArgumentNotEqualsAnnotationState extends State {
		public State parse(char character) {
			if (character == '=') {
				return argumentNotEqualsState.parse(character);
			} else {
				expressionBuilder.addOperatorToken(expressionBuilder.ANNOTATION);
				expressionBuilder.addOperatorToken(expressionBuilder.NOT);
				return argumentValueState.parse(character);
			}
		}
	}

	private final ArgumentAndState argumentAndState = new ArgumentAndState();
	private class ArgumentAndState extends State {
		public State parse(char character) {
			if (character == '&') {
				expressionBuilder.addOperatorToken(expressionBuilder.LOGICAL_AND);
				return argumentValueState;
			} else {
				expressionBuilder.addOperatorToken(expressionBuilder.AND);
				return argumentValueState.parse(character);
			}
		}
	}

	private final ArgumentOrState argumentOrState = new ArgumentOrState();
	private class ArgumentOrState extends State {
		public State parse(char character) {
			if (character == '|') {
				expressionBuilder.addOperatorToken(expressionBuilder.LOGICAL_OR);
				return argumentValueState;
			} else {
				expressionBuilder.addOperatorToken(expressionBuilder.OR);
				return argumentValueState.parse(character);
			}
		}
	}

	private final CommentReadState commentReadState = new CommentReadState();
	private class CommentReadState extends State {
		public State parse(char character) {
			if (character == '\n' || character == '\0') {
				lineBuilder.setComment(accumulator.toString());
				accumulator.setLength(0);
				return labelStartState;
			} else {
				accumulator.append(character);
				return commentReadState;
			}
		}
	}

	private final CommentReadThenArgumentState commentReadThenArgumentState = new CommentReadThenArgumentState();
	private class CommentReadThenArgumentState extends State {
		public State parse(char character) {
			if (character == '\n' || character == '\0') {
				lineBuilder.setComment(accumulator.toString());
				accumulator.setLength(0);
				return argumentValueState.parse(character);
			} else {
				accumulator.append(character);
				return commentReadThenArgumentState;
			}
		}
	}

	private final CommentReadThenOperatorState commentReadThenOperatorState = new CommentReadThenOperatorState();
	private class CommentReadThenOperatorState extends State {
		public State parse(char character) {
			if (character == '\n' || character == '\0') {
				lineBuilder.setComment(accumulator.toString());
				accumulator.setLength(0);
				return argumentOperatorState.parse(character);
			} else {
				accumulator.append(character);
				return commentReadThenOperatorState;
			}
		}
	}

	private static int parseInt(String string, int radix) {
		try {
			long value = Long.parseLong(string, radix);
			if (value > 0xFFFFFFFFL)
				throw new SyntaxError();
			return (int)value;
		} catch (NumberFormatException e) {
			throw new SyntaxError();
		}
	}

	public static class SyntaxError extends AssemblyException {
		@Serial private static final long serialVersionUID = 1L;

		public SyntaxError() {
			this(null);
		}

		public SyntaxError(Throwable cause) {
			super("Syntax error.", cause);
		}

	}

}
