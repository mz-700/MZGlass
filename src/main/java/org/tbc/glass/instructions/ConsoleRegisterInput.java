package org.tbc.glass.instructions;

import org.tbc.glass.Scope;
import org.tbc.glass.expressions.Expression;

public final class ConsoleRegisterInput extends InstructionFactory {
    @Override
    public InstructionObject createObject(Scope context, Expression arguments) {
        return ConsoleRegisterOutput.encode(context, arguments, (byte) 0x03);
    }
}
