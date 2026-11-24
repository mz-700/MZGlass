package org.tbc.glass.instructions;

import org.tbc.glass.Scope;
import org.tbc.glass.expressions.Expression;
import org.tbc.glass.expressions.IntegerLiteral;

/** Emits the opcode for a console string stored immediately after it in memory. */
public final class ConsoleStringOutput extends InstructionFactory {
    @Override
    public InstructionObject createObject(Scope context, Expression arguments) {
        if (arguments != null) throw new ArgumentException("Follow C_SOUT with DB data terminated by $0D.");
        return new Output(context);
    }

    private static final class Output extends InstructionObject {
        private Output(Scope context) { super(context); }

        @Override public Expression getSize() { return IntegerLiteral.TWO; }

        @Override public byte[] getBytes() { return new byte[] {(byte) 0xed, 0x02}; }
    }
}
