package org.tbc.glass.instructions;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.tbc.glass.AssemblyException;
import org.tbc.glass.Line;
import org.tbc.glass.Scope;
import org.tbc.glass.Source;
import org.tbc.glass.expressions.Expression;
import org.tbc.glass.expressions.Identifier;
import org.tbc.glass.expressions.IntegerLiteral;
import org.tbc.glass.expressions.Schema;
import org.tbc.glass.expressions.SectionContext;
import org.tbc.glass.api.Emission;
import org.tbc.glass.api.SourceRange;

public class Ds extends InstructionFactory implements SectionContext {

	public static final Schema ARGUMENTS_N = new Schema( Schema.INTEGER);
	public static final Schema ARGUMENTS_N_N = new Schema( Schema.INTEGER, Schema.INTEGER);
	public static final Schema ARGUMENTS_VIRTUAL = new Schema( new Schema.IsAnnotation( Schema.INTEGER));

	private final List<Section> sections = new ArrayList<>();

	@Override
	public void addSection(Section section) {
		sections.add(section);
	}

	public List<Section> getSections() {
		return sections;
	}

	@Override
	public InstructionObject createObject(Scope context, Expression arguments) {
		if (ARGUMENTS_N.check(arguments))
			return new Ds_N_N(context, null, arguments, IntegerLiteral.ZERO);
		if (ARGUMENTS_N_N.check(arguments))
			return new Ds_N_N(context, null, arguments.getElement(0), arguments.getElement(1));
		if (ARGUMENTS_VIRTUAL.check(arguments))
			return new Ds_N_N(context, arguments.getAnnotation(), arguments.getAnnotee(), IntegerLiteral.ZERO);
		throw new ArgumentException();
	}

	public class Ds_N_N extends InstructionObject {

		private final boolean virtual;
		private final Expression size;
		private final Expression value;

		public Ds_N_N(Scope context, Identifier annotation, Expression size, Expression value) {
			super(context);
			this.virtual = annotation != null && ("virtual".equals(annotation.getName()) || "VIRTUAL".equals(annotation.getName()));
			this.size = size;
			this.value = value;

			if (annotation != null && !virtual)
				throw new ArgumentException("Unsupported annotation: " + annotation.getName());
		}

		@Override
		public Expression resolve(Expression address) {
			Expression innerAddress = address;
			for (Section section : sections)
				innerAddress = section.getSource().resolve(innerAddress);
			return super.resolve(address);
		}

		@Override
		public Expression getSize() {
			return size;
		}

		@Override
		public byte[] getBytes() {
			return getBytes(0);
		}

		@Override
		public byte[] getBytes(int originFill) {
			ByteArrayOutputStream sourceByteStream = new ByteArrayOutputStream(size.getInteger());
			byte[] sectionBytes = Source.getSectionBytes(sections.stream().map(Section::getSource).toList(),
					context.getAddress(), originFill);
			sourceByteStream.write(sectionBytes, 0, sectionBytes.length);

			if (sourceByteStream.size() > size.getInteger())
				throw new AssemblyException("Section size exceeds space (required: " +
					sourceByteStream.size() + " bytes, available: " + size.getInteger() + " bytes).");

			if (virtual)
				return new byte[] {};

			byte[] padding = new byte[size.getInteger() - sourceByteStream.size()];
			Arrays.fill(padding, (byte)value.getInteger());
			sourceByteStream.write(padding, 0, padding.length);

			return sourceByteStream.toByteArray();
		}

		/** Keeps nested section/PHASE/ORG mappings, and distinguishes explicit DS fill from origin padding. */
		public Source.ObjectCode getObjectCode(int originFill, SourceRange location) {
			return getObjectCode(originFill, location, false, null);
		}

		public Source.ObjectCode getObjectCode(int originFill, SourceRange location, boolean ignoreConsoleInstructions,
				Map<Line, byte[]> consoleReplacements) {
			var nested = Source.getSectionObjectCode(sections.stream().map(Section::getSource).toList(),
					context.getAddress(), originFill, !virtual && ignoreConsoleInstructions,
					virtual ? null : consoleReplacements);
			byte[] section=nested.bytes(); int length=size.getInteger();
			if(section.length>length) throw new AssemblyException("Section size exceeds space (required: " + section.length + " bytes, available: " + length + " bytes).");
			if(virtual) return new Source.ObjectCode(new byte[0],List.of());
			byte[] object=Arrays.copyOf(section,length); Arrays.fill(object,section.length,length,(byte)value.getInteger());
			List<Emission> emissions=new ArrayList<>(nested.emissions());
			if(length>section.length) emissions.add(new Emission(section.length,length-section.length,
					(long)context.getAddress().getInteger()+section.length,Emission.Kind.DATA,location));
			return new Source.ObjectCode(object,emissions);
		}

	}

}
