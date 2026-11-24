package org.tbc.glass.instructions;

import org.tbc.glass.Scope;
import org.tbc.glass.expressions.Expression;

public final class ConsoleStringInput extends InstructionFactory {
    @Override
    public InstructionObject createObject(Scope context, Expression arguments) {
        return ConsoleMemoryOutput.encode(context, arguments, (byte) 0x05);
    }
}
