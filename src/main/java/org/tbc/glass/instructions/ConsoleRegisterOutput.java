package org.tbc.glass.instructions;

import org.tbc.glass.Scope;
import org.tbc.glass.expressions.Expression;
import org.tbc.glass.expressions.Identifier;
import org.tbc.glass.expressions.IntegerLiteral;

import java.util.List;
import java.util.Locale;

/** MZide and APP-E700 console extension. List positions are the encoded selector bytes. */
public final class ConsoleRegisterOutput extends InstructionFactory {
    private static final List<String> REGISTERS = List.of(
            "A", "B", "C", "D", "E", "F", "H", "L",
            "A'", "B'", "C'", "D'", "E'", "F'", "H'", "L'",
            "AF", "BC", "DE", "HL", "AF'", "BC'", "DE'", "HL'",
            "IX", "IY", "IXL", "IXH", "IYL", "IYH", "PC", "SP", "I", "R");

    public static List<String> registerNames() { return REGISTERS; }

    static int selector(Expression operand) {
        // Match the spelling here so console-only register names remain usable as labels elsewhere.
        return operand instanceof Identifier register ? REGISTERS.indexOf(register.getName().toUpperCase(Locale.ROOT)) : -1;
    }

    @Override
    public InstructionObject createObject(Scope context, Expression arguments) {
        return encode(context, arguments, (byte) 0x00);
    }

    static InstructionObject encode(Scope context, Expression arguments, byte opcode) {
        int selector = selector(arguments);
        if (selector < 0) throw new ArgumentException();
        return new RegisterInstruction(context, opcode, (byte) selector);
    }

    private static final class RegisterInstruction extends InstructionObject {
        private final byte opcode;
        private final byte selector;

        private RegisterInstruction(Scope context, byte opcode, byte selector) {
            super(context);
            this.opcode = opcode;
            this.selector = selector;
        }

        @Override public Expression getSize() { return IntegerLiteral.THREE; }
        @Override public byte[] getBytes() { return new byte[] {(byte) 0xed, opcode, selector}; }
    }
}
