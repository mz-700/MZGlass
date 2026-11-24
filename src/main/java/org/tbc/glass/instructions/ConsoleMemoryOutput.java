package org.tbc.glass.instructions;

import org.tbc.glass.Scope;
import org.tbc.glass.expressions.Expression;
import org.tbc.glass.expressions.IntegerLiteral;
import org.tbc.glass.expressions.Sequence;

import java.util.List;

/** Dumps bytes at an address held in a register using the C_ROUT selector table. */
public final class ConsoleMemoryOutput extends InstructionFactory {
    private static final List<String> REGISTERS = List.of("BC", "DE", "HL", "IX", "IY", "BC'", "DE'", "HL'", "SP", "PC");

    public static List<String> registerNames() { return REGISTERS; }

    @Override
    public InstructionObject createObject(Scope context, Expression arguments) {
        return encode(context, arguments, (byte) 0x01);
    }

    static InstructionObject encode(Scope context, Expression arguments, byte opcode) {
        if (!(arguments instanceof Sequence operands) || operands.getTail() instanceof Sequence) throw new ArgumentException();
        int selector = ConsoleRegisterOutput.selector(operands.getHead());
        if (selector < 0 || !REGISTERS.contains(ConsoleRegisterOutput.registerNames().get(selector))) throw new ArgumentException();
        return new MemoryInstruction(context, opcode, (byte) selector, operands.getTail());
    }

    private static final class MemoryInstruction extends InstructionObject {
        private final byte opcode;
        private final byte selector;
        private final Expression length;

        private MemoryInstruction(Scope context, byte opcode, byte selector, Expression length) {
            super(context);
            this.opcode = opcode;
            this.selector = selector;
            this.length = length;
        }

        @Override public Expression getSize() { return IntegerLiteral.FOUR; }

        @Override public byte[] getBytes() {
            int bytes = length.getInteger();
            if (bytes < 0 || bytes > 255) {
                String mnemonic = switch (opcode) {
                    case 0x01 -> "C_MOUT";
                    case 0x04 -> "C_MIN";
                    case 0x05 -> "C_SIN";
                    default -> throw new IllegalStateException("Unsupported console memory opcode: " + opcode);
                };
                throw new ArgumentException(mnemonic + " length must be between 0 and 255.");
            }
            return new byte[] {(byte) 0xed, opcode, selector, (byte) bytes};
        }
    }
}
