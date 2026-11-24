MZGlass Z80 assembler
===================

Project information
-------------------

This project is a fork from Glass Assembler. Copyright 2013 Laurens Holst (laurens@grauw.nl).
The original project is available at <https://github.com/grauw/glass>. The license is [Simplified BSD License](https://opensource.org/license/bsd-2-clause).

MZGlass is a cross-assembler for the Z80 processor with special features for the Sharp MZ 700.
It is written in Java 25. Its core
principles are to be open source, cross-platform, and to provide a standard Z80
syntax infused with modern language features.

It presents a flexible language for Z80 object code generation by building an
abstract syntax tree with strong scoping rules, rather than using the
traditional multi-pass architecture with separate preprocessor and mnemonic
translation. This allows the user to write powerful expressions, use macros as
type definitions, etc. Future developments aim to bring more modern programming
concepts to the Z80 assembly programming realm.

Because the binary is a jar which runs on the Java virtual machine, it can be
included in a project easily without requiring the user to acquire a separate
binary build for their operating system.

Usage
-----

To run Glass from the command line, use the following command.

    java -jar glass.jar [OPTION] SOURCE [OBJECT] [SYMBOL]

Source specifies the source file, object the output file, and symbol a text file
which will hold a list of symbols and their addresses in the output.

Options:

  * `--all-errors` Report independent errors that can be checked after recovery.
    By default, the command line stops at the first error. Failed builds in
    this mode exit with status 1 and leave output files untouched.

  * `--ignore-console-instructions` Replace the six emulator Console instructions
    with the same number of NOP bytes. For `C_SOUT`, the assembler also replaces
    its inline data through the first `$0D` byte. The terminator must appear before
    the next instruction or output gap.

  * `-I include_path` Include path for additional source files.
    
  * `-L list_file` File to output a listing of the assembled code to.

  * `-D debug_file` File to output debug information to. This file is used by the MZ700 Debugger.

  * `-O type` Output type. Supported values are `BIN` for a raw binary file
    (the default), and `MZF` for a Sharp MZ-700 program file.

  * `-F byte` Fill byte for forward `ORG` gaps, default `0`. Accepts decimal,
    `$FF` or `0xFF` notation, in the range 0–255. For ROM-oriented output use
    `-F 0xFF`. It does not alter explicit data, `DS` fill or phased code.

Note that [Java 25](http://java.com/getjava) must be installed to run Glass.
To check your Java version, invoke the `java -version` command.

Java library API
----------------

MZGlass can also be used in-process through `org.tbc.glass.api`. The Maven
coordinates for the development version are `org.tbc:MZGlass:1.4.1-SNAPSHOT`;
install it locally with `mvn install`. The Java module name is `org.tbc.glass`.
The executable JAR and the existing command-line options remain available.

```java
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.tbc.glass.api.AssemblyRequest;
import org.tbc.glass.api.AssemblyResult;
import org.tbc.glass.api.AssemblyService;

Path main = Path.of("project/main.asm").toAbsolutePath().normalize();
AssemblyRequest request = new AssemblyRequest(main, List.of(), Map.of(
    main, " org $1200\nMain: ld a,42\n ret\n"
));
AssemblyResult result = new AssemblyService().assemble(request);
if (result.succeeded()) {
    byte[] objectCode = result.objectCode();
} else {
    result.diagnostics().forEach(diagnostic -> {
        System.err.println(diagnostic.message() + " at " + diagnostic.range());
    });
}
```

`AssemblyRequest` captures an entry source path, include search paths and a map
of unsaved source text keyed by its original path. Relative paths are relative
to the process working directory. A source override can name a file or directory
that does not exist on disk. Included source files use the same overrides; other
inputs fall back to the filesystem. Include lookup searches the including
source's directory first, followed by the configured include paths in order.
`INCBIN` uses raw binary inputs from the fallback provider, not source-text
overrides. A custom `SourceProvider` can supply source and binary inputs from
another store.

Each service call creates a fresh assembly session. Include-once state is shared
by nested includes within that session and is discarded between calls. Ordinary
recursive include cycles produce an assembly error. Source and binary reads are
cached on first access within a session; callers needing an atomic snapshot of
all disk inputs should supply a snapshot provider. Separate calls may run
concurrently; a custom provider shared between calls must support that use.

The result contains raw object bytes and immutable diagnostics, with defensive
copies of byte arrays. Warnings are returned as diagnostics rather than printed
by the library API. Failed assembly returns no partial bytes. Strict assembly
returns the first fatal error. To build MZF bytes in memory, use
`assemble(request, SourceProvider.fileSystem(), Assembler.OutputType.MZF)`.
The default remains BIN. Both paths share the command-line object encoder; MZF
metadata and size errors become structured diagnostics. The service writes no
files. The command-line assembler also retains symbol, listing and debug output.

Editors can call `analyze(request, provider)` on each source snapshot. It recovers
from syntax errors at the next physical line. After reporting a missing include,
it continues with the rest of the source and its other includes. Symbol
registration errors such as duplicate labels are reported as well, and checking
continues. The live check validates independent built-in operand forms in the
parsed code and skips operand checking
inside unresolved conditional and macro blocks. When recovery was needed, it
returns no object bytes and defers checks that require a complete assembly.
Snapshots that need no recovery use the normal assembly passes and return raw
BIN bytes on success.

Forward `ORG` gaps are included in BIN/MZF output and the MZF payload-size field.
Existing API calls use zero fill. To select a different gap byte, use
`assemble(request, provider, outputType, originFill)`; for example, pass `255`
for `$FF` gaps when preparing ROM data. The lower-level equivalents are
`new Assembler(path, includePaths, originFill)`,
`source.assemble(output, originFill)` and `source.getBytes(originFill)`.
Fill settings are per call; no global mutable assembly configuration is introduced.

Diagnostic ranges use zero-based UTF-16 offsets in LF-normalized source text,
with exclusive ends. The primary range is the innermost error context; additional
include/macro contexts are in `related()`. Parser columns identify a character
(or a zero-width end-of-line range). Errors without a specific column identify
the logical statement. Input failures without source context use range `0..0`
at the entry file. Source spans expose their line/column values and `toRange()`
for callers using the lower-level assembler classes directly.

Use `mvn verify` to run the assembler and API regression tests. A modular consumer
declares `requires org.tbc.glass;`; the modular JAR can be included in a `jlink`
runtime image.

Symbol file format
------------------

The optional symbol file is a text file containing one resolved integer symbol
per line:

    symbol: equ value

Symbols are sorted alphabetically. Nested and local scopes are represented with
dot-separated names. Values are written as hexadecimal numbers with an `H`
suffix; hexadecimal values beginning with `A` through `F` receive a leading
zero so that the output is valid assembler syntax. Negative values have a
leading minus sign.

For example:

    Main.loop: equ 120AH
    PLAYER.health: equ 2H
    SCREEN: equ 0D000H
    adjustment: equ -10H

Only symbols that resolve to integer values are included. The file can be
included in another assembly source file because every entry uses the standard
`equ` syntax.

List file format
----------------

The list file is a tab-separated text listing of the assembled source. A source
header is emitted whenever the listing switches to another file:

    # source: path/to/source.asm

Each following line contains the source line number, assembly address, emitted
object bytes, and source text:

    line	address	byte1	byte2	byte3	byte4	source

For example:

       1	1200	00					start: nop
       2	1201	C3	00	12			jp start

Line numbers and addresses are displayed in decimal and hexadecimal,
respectively. Object bytes are uppercase hexadecimal and occupy tab-separated
columns. Four byte columns are padded when fewer than four bytes are emitted;
instructions or data that emit more than four bytes continue into additional
columns. Lines that emit no object code have an empty object field.

Macro invocations contain `MACRO` in the object column, followed by the
expanded lines. Expanded macro lines show their substituted arguments. Included
files receive their own `# source:` header, and section contents are listed at
the point where they are assembled.

Debug file format
-----------------

The debug file selected with `-D` is a plain-text, decimal-address format used
by the MZ700 Debugger. It consists of four sections:

    ; LABELS

    ; MAP

    ; USAGES

    ; MACROS

Blank lines separate the headers from their entries.

The `LABELS` section maps symbols to their source locations, kinds, and values:

    name: absolute-path,line,type,value

`line` is one-based. `type` is `LABEL` for code or general labels, `DS` for
reserved storage, `DC` for data created by `db`, `dw`, `dd`, `mz_asc`, or
`mz_disp`, and `EQU` for constants. The final field is the decimal address for
`LABEL`, `DS`, and `DC`, and the decimal value for a resolved `EQU`. If an
`EQU` cannot be reduced to an integer, its expression is written instead.
Local symbols use dot-qualified names such as `Main.loop`.

The `MAP` section describes assembled address ranges:

    start:end:type

`start` is inclusive, `end` is exclusive, and `type` is `code`, `data`, or
`string`. `string` is used for `db`, `mz_asc`, and `mz_disp` lines whose
arguments contain a string value, such as `db "bar"`. Numeric-only uses of
those mnemonics are still `data`. Adjacent ranges of the same type are combined.
If ranges overlap, `code` takes precedence over `string`, and `string` takes
precedence over `data`.

The `USAGES` section lists the assembled addresses that reference each label:

    name:address,address,...

Each address appears at most once per label and is the address of the
instruction or data line containing the reference. References in `equ`
definitions are not included.

The `MACROS` section maps macro definitions to their source locations:

    name: absolute-path,line

For example, a debug file may contain:

    ; LABELS

    start: C:\project\main.asm,1,LABEL,4608
    message: C:\project\main.asm,4,DC,4612

    ; MAP

    4608:4612:code
    4612:4624:data

    ; USAGES

    start:4609

    ; MACROS

    PRINT: C:\project\macros.asm,12

Syntax
------

The assembler syntax follows common style.

Lines are composed as follows:

    label: mnemonic arguments ;comment

Note that the white space before the mnemonic is significant; otherwise, it will
be interpreted as a label.

All identifiers are case-sensitive. Mnemonics of built-in instructions,
directives, registers, flags and annotations are recognised in lowercase and
uppercase, but can not be mixed case.

Labels
------

Labels and other identifiers follow the following grammar:

    identifier = id_start_char id_char*
    id_start_char = [a-z] | [A-Z] | _ | . | ? | @
    id_char = id_start_char | [0-9] | ' | $

The colon after a label is optional. If a label has no colon, it can not have
any leading white space, it must start at column 0.

Instructions
------------

Standard z80 instruction syntax is used:

    ld a,(ix + 10)

Parentheses are used to indicate indirection.

  * Z80 instructions: `adc`, `add`, `and`, `bit`, `call`, `ccf`, `cp`, `cpd`,
    `cpdr`, `cpi`, `cpir`, `cpl`, `daa`, `dec`, `di`, `djnz`, `ei`, `ex`, `exx`,
    `halt`, `im`, `in`, `inc`, `ind`, `indr`, `ini`, `inir`, `jp`, `jr`, `ld`,
    `ldd`, `lddr`, `ldi`, `ldir`, `neg`, `nop`, `or`, `otdr`, `otir`, `out`,
    `outd`, `outi`, `pop`, `push`, `res`, `ret`, `reti`, `retn`, `rl`, `rla`,
    `rlc`, `rlca`, `rld`, `rr`, `rra`, `rrc`, `rrca`, `rrd`, `rst`, `sbc`,
    `scf`, `set`, `sla`, `sra`, `srl`, `sub`, `xor`
    
    For a complete description of the Z80 instruction set, see the official
    Zilog documentation: <http://www.zilog.com/docs/z80/um0080.pdf>
    
    In addition to the documented Z80 instructions, the variations using the
    undocumented `ixh`, `ixl`, `iyh` and `iyl` index registers are supported, as
    well as the semi-documented `in (c)`.
    
    For register jumps, `jp (hl)` etc., the parentheses are optional.
    
  * R800 instructions: `mulub`, `muluw`
    
    R800 multiplication instructions.
    
  * Define byte: `db`
    
    Defines a byte or a sequence of bytes.

  * Define MZ-700 text: `mz_asc`
    
    Defines bytes like `db`, but maps lowercase ASCII characters to the
    Sharp MZ-700 lowercase character codes.

  * Define MZ-700 display text: `mz_disp`
    
    Defines bytes like `mz_asc`, but maps characters to the display codes used
    when writing directly to video memory.
    
  * Define word: `dw`
    
    Defines a word or a sequence of words.
    
  * Define double word: `dd`
    
    Defines a double word or a sequence of double words.
    
  * Define space: `ds`
    
    Defines space for a number of bytes. The first argument indicates the number
    of bytes, the optional second argument specifies the fill value (default 0).
    
    The first argument can be annotated with `virtual`, in which case the
    address counter will be incremented accordingly, but no object is actually
    generated in the output. If the virtual annotation is given, you can not
    specify a fill value.

Directives
----------

#### `org`: Origin

The initial `ORG` establishes the payload base without padding from zero to that
address. A subsequent forward `ORG` inserts padding from the physical output end
to the requested origin. The default padding byte is `$00`; `-F` or the library
fill argument can select another byte. A same-address origin adds no padding.

    org $1200
    db $AA
    org $1204
    db $BB

This produces `AA 00 00 00 BB`, with three padding bytes for `$1201..$1203`.
Padding participates in object length, MZF payload size and reserved-section
capacity checks. Includes, macros and selected conditional branches share the
surrounding output position; they do not restart the file at each origin.
A `SECTION` remains anchored to its reserved `DS` range, including its first origin.

Backward/overlapping physical origins are assembly errors, not implicit rewinds
or overwrites. An initial origin outside the 16-bit range remains supported;
output-gap arithmetic is checked rather than wrapped.

`PHASE`/`DEPHASE` emits code/data contiguously without origin padding. The existing
logical-counter restoration behavior described below is unchanged. An `ORG`
inside an active phase continues to change its logical counter only; an `ORG`
after `DEPHASE` measures its physical gap from all bytes actually emitted,
including the phased block.

This intentionally corrects earlier MZGlass behavior that concatenated physical
`ORG` sections without their gaps. Both command-line and in-process assembly use
the corrected layout.

#### `phase`, `dephase`: Phased code
    
Temporarily changes the address used for labels and `$`, without advancing
the surrounding address location counter. Object code is still emitted at
the current output position.

This is useful for code that is stored in one place in the output file, but
will be copied, banked, or mapped to another address before it is executed.
    
        org $1200
        nop
        nop
 
        phase $3000
    label:
        ld hl,label
        dephase

    label2:
        ld bc,label2

In this example, the two `nop` instructions are assembled at `$1200` and
`$1201`. The phased block is emitted next in the output file, but labels
inside it are resolved as if the code starts at `$3000`, so `ld hl,label`
loads `$3000`. After `dephase`, assembly resumes at the original address,
so `label2` is `$1202`.

A phase block can also specify a maximum size:

        phase $3000,$100
        ; phased code here must fit in $100 bytes
        dephase

If the phased code exceeds the limit before `dephase`, assembly fails. It is
also an error to start a new `phase` or bank block before ending the current
one.

#### `bank_a`, `bank_b`, and `bank_c`: use with the Sharp MZ700 ST-110 MMU board

These are aliases for phased blocks starting at `$D000`, `$E010`, and `$F000`, respectively.
`bank_a` and `bank_c` default to a maximum size of 4096 bytes, and `bank_b` defaults to 4080
bytes. A numeric argument overrides the default size, and `size_off` disables the size limit.
`end_bank` is an alias for `dephase`.

        bank_a
    BankRoutine:
        ld hl,BankRoutine
        ret
        end_bank

        bank_b 512
        ; this bank block must fit in 512 bytes
        end_bank

        bank_c size_off
        ; no explicit size limit
        end_bank

#### `rel`, `endrel`: Relocatable code block

Assembles the enclosed block as relocatable code. The code inside the block
is resolved as if it starts at address 0, then the assembler emits a small
wrapper before the actual code:

        jp code_start
        dw patch_offset1,patch_offset2,...
    code_start:
        ; assembled block bytes

The relocation table contains the offsets of 16-bit operands that reference
labels defined inside the `rel` block. These offsets are relative to the
start of the actual code, not the start of the wrapper. The values stored in
the operands are also the block-local label offsets.

        rel
            ld hl,label1
            nop
            ld de,label2
    label1:
            nop
    label2:
            nop
            endrel

This emits a jump over the relocation table, then two table entries: the
offset of the word operand in `ld hl,label1`, and the offset of the word
operand in `ld de,label2`.

A small relocation routine can use the initial jump target to know where the
table ends. In this example, `HL` points to the start of the generated `rel`
block, and `DE` is the address where the actual code bytes will run. The
routine assumes the actual code bytes have already been copied there.

        ; input:
        ;   HL = start of generated REL block
        ;   DE = runtime address of the actual code
        ;
        ; patches every word listed in the relocation table by adding DE
        ; to the block-local value already assembled in the code.
    RelocateRel:
        push de
        pop ix              ; IX = runtime code base

        inc hl              ; skip JP opcode
        ld c,(hl)
        inc hl
        ld b,(hl)           ; BC = offset of actual code
        inc hl              ; HL = first relocation table entry

        dec bc
        dec bc
        dec bc              ; BC = relocation table size in bytes

    RelocateRelLoop:
        ld a,b
        or c
        ret z

        ld e,(hl)
        inc hl
        ld d,(hl)
        inc hl              ; DE = patch offset, HL = next table entry

        push hl
        push bc

        push ix
        pop hl              ; HL = runtime code base
        add hl,de           ; HL = address of word to patch

        ld e,(hl)
        inc hl
        ld d,(hl)           ; DE = block-local word value

        push ix
        pop bc              ; BC = runtime code base
        ex de,hl            ; HL = block-local word value, DE = high byte address
        add hl,bc           ; HL = relocated word value

        ld a,h
        ld (de),a
        dec de
        ld a,l
        ld (de),a

        pop bc
        pop hl
        dec bc
        dec bc
        jr RelocateRelLoop

#### `mzf_title`, `mzf_load`, `mzf_start`, `mzf_comments`: MZF output metadata
    
Specifies metadata for `-O MZF` output.\
`mzf_load` is required. `mzf_start` defaults to the load address, `mzf_title` defaults to `TITLE`, and
`mzf_comments` defaults to an empty field. If these directives appear multiple times, the last value is used.

The two-byte little-endian size at header offset `$12` is the binary payload size,
excluding the 128-byte header. Payloads can contain up to 65,535 bytes; the complete
file is 128 bytes longer. Loading still requires the payload to fit the target's
address space at the selected load address.
    
        mzf_title the game!
        mzf_load $1200
        mzf_start $1200
        mzf_comments comments
    
#### `equ`: Assign constant
    
Assigns a constant value to a symbol.
    
        JIFFY: equ $FC9E
    
#### `include`: Include
    
Includes another source file. The current working directory is searched, as
well as any include paths specified on the command line.
    
        INCLUDE "math.asm"
    
Optionally you can specify a `once` annotation to prevent a file from being
included more than once. However it is not recommended to use unless needed.
    
        INCLUDE ONCE "math.asm"
    
#### `incbin`: Include binary
    
Includes binary data from a file. The current working directory is searched,
as well as any include paths specified on the command line.
    
        INCBIN "image.ge5"
    
Optionally you can specify a start position and length:
    
        INCBIN "image.ge5",7,212*128
    
#### `macro`, `endm`: Macros
    
Defines a macro instruction, composed of all the instructions that follow
until the `endm` directive is encountered. The definition’s arguments
specify the parameters which are passed when the macro is invoked.
    
        ALIGN: MACRO ?boundary
               ds ?boundary - 1 - ($ + ?boundary - 1) % ?boundary
               ENDM
               
               ALIGN 100H
    
All symbols defined in a macro block are local. Symbols in macro instances
can be referenced by using the `.` operator.
    
Default values for macro arguments can be specified with `=`:
    
        ALIGN: MACRO ?boundary = 100H

Macros can be used to define convenient pseudo-instructions. Arguments are
expressions, so registers, labels, constants, and parenthesized operands can
be passed through to the expanded code.

        LDW: MACRO dest,src
             push src
             pop dest
             ENDM

             LDW bc,hl       ; expands to: push hl / pop bc

Macro parameters can also describe a required parenthesized argument. The
parentheses are matched at the call site, and the inner expression is bound
to the parameter:

        LDB: MACRO dest,(src)
             push hl
             LDW hl,src
             ld dest,(hl)
             pop hl
             ENDM

             LDB c,(de)

In this example, `dest` is `c`, and `src` is `de`. Calling `LDB c,de`
would be rejected because the second argument is required to be
parenthesized.

Macro arguments can be tested with `if`. Register arguments can be compared
with `=` and `!=`, which is useful for validating pseudo-instruction
operands:

        ADDB: MACRO dest,src
             IF dest = a
                 ERROR "Invalid destination. A is not allowed."
             ELSE
                 ld (SAVE_A),a
                 ld a,dest
                 add a,src
                 ld dest,a
                 ld a,(SAVE_A)
             ENDIF
             ENDM

             ADDB c,e

        SAVE_A: ds 1

Macro definitions do not define structure offsets. Use `struct` for data
layouts.

#### `struct`, `endstruct`: Structures

Defines a structure layout. The contents are assembled virtually at address
0, turning the inner symbols into offsets. The structure itself does not
emit object code.

        PLAYER: STRUCT
        x:       db 0
        y:       db 0
        health:  db 0
        score:   dw 0
        _size:   ENDSTRUCT

The labels inside the structure become offsets:

        PLAYER.x       ; 0
        PLAYER.y       ; 1
        PLAYER.health  ; 2
        PLAYER.score   ; 3
        PLAYER._size   ; 5

You can allocate storage for an instance and access fields by adding the
offsets to the instance address:

    player1:
        ds PLAYER._size

        ld hl,player1 + PLAYER.health
        ld (hl),100

If an index register points to an instance, the offsets can be used directly
in indexed addressing:

        ld ix,player1
        ld a,(ix + PLAYER.x)
        ld b,(ix + PLAYER.y)
        ld (ix + PLAYER.health),100
    
#### `rept`, `endm`: Repetition
    
Repeats a section of code a number of times. The end of the section is
marked with the `endm` directive. The first argument is mandatory and
specifies the number of repeats. The second argument specifies a counter
parameter, the third the initial value for the counter (default: 0), and the
fourth argument specifies the counter increment (default: 1).
    
        REPT 10, ?counter, 0, 2
        ld bc,(table + ?counter)
        REPT 3
        add hl,bc
        ENDM
        ENDM
    
All symbols defined in a repeat block are local. If a repeat is labeled,
the inner repeat scopes can be accessed by index, e.g.: `mylist.0`.
    
#### `irp`, `endm`: Indefinite repetition
    
Repeats a section of code for each of the arguments specified. The end of
the section is marked with the `endm` directive. The first argument is
mandatory and specifies the parameter the current repetition’s value is
passed to. The remaining arguments are passed one by one as the section is
repeated.
    
        IRP ?value, 1, 2, 4, 8, 16, 32, 64, 128
        or ?value
        ENDM
    
All symbols defined in a indefinite repeat block are local. If a repeat is
labeled, the inner repeat scopes can be accessed by index, e.g.: `mylist.0`.
    
#### Dot-local labels

In ordinary source, a label starting with a dot belongs to the most recent
label whose name begins without a dot. References such as `.wait` use the
same owner, including forward references. Use the qualified name, such as
`first.wait`, to reference it from elsewhere.

```asm
first:  jr .wait
.wait:  ret
second: jr .wait
.wait:  ret
        dw first.wait, second.wait
```

Each source file starts with no owner, and an include leaves the caller's
owner as it was. A leading-dot name before the first owner retains its literal
spelling. An `IF` branch or `SECTION` inherits the current owner and restores
it on exit. Explicit procedure, macro, repeat, structure and relocation scopes
keep their own symbol rules. For example, a literal `.wait` in `routine: PROC`
is still available as `routine..wait`.

#### `proc`, `endp`:  Procedure
    
Defines a section of code as a procedure. Currently mostly serves to
establish a local scope.
    
        shift5: PROC
                ld b,5
                jp shiftl.loop
                ENDP
                
        shiftl: PROC
                ld b,1
        loop:   add a,a
                djnz loop
                ret
                ENDP
    
All symbols defined in a procedure block are local. Symbols in inner scopes
can be referenced by using the `.` operator.
    
#### `if`, `else`, `endif`: Condition
    
Conditionally assembles a section of code, or an optional alternative
section. The end of the section is either marked with `endif`, or with
`else` in which case an alternative will follow up to the `endif`. The
argument is evaluated as an integer, and if the result is nonzero (true) the
first section is assembled, and if the result is zero (false) the
alternative is assembled if one is provided.
    
        PAD: MACRO ?address
             IF $ > ?address
                 ERROR "Padding address exceeded."
             ELSE
                 ds ?address - $
             ENDIF
             ENDM
    
#### `end`: Source file end
    
Indicates the end of the current source file. Specifying this is optional,
and usually omitted. Any content on lines beyond this directive will not be
parsed.
    
#### `error`: Error
    
Generates an error and aborts the compilation. Optionally a message can be
specified.
    
        ERROR "Limit exceeded."
    
#### `warning`: Warning
    
Generates a warning. Optionally a message can be specified.
    
        WARNING "Nearly out of space."
    
#### `section`: Section
    
Defines a section of code or data that will be assembled inside the space of
a ds statement. This allows you to have nonadjacent code or data sections
and group them into separate regions, such as ROM and RAM pages. The
mandatory argument references the DS statement that is the target of the
section.
    
            org 4000H
        ROM_PAGE1: ds 4000H
        ROM_PAGE2: ds 4000H
        RAM: ds VIRTUAL 3000H
        
            SECTION ROM_PAGE1
        SetValue:
            ld (value),a
            ret
        
            SECTION RAM
        value: db 0
            ENDS
            ENDS

Literals
--------

  * Decimal: `127`
  * Hexadecimal: `0FC9EH`, `#FC9E`, `$FC9E` or `0xFC9E`
  * Binary: `10110001B` or `%10110001`
  * Octal: `377O`
  * Character: `'c'`
  * String: `"abc"`

Character literals can contain the `'` character by repeating it as `''`, and
string literals can contain the `"` character by repeating it as `""`.

Character and string literals support the following escape sequences:

  * `\0` (NUL)
  * `\a` (bell)
  * `\t` (tab)
  * `\n` (line feed)
  * `\f` (form feed)
  * `\r` (carriage return)
  * `\e` (escape)
  * `\"` (double quotation mark)
  * `\'` (single quotation mark)
  * `\\` (backslash)

Numeric escape sequences are not supported. In stead, you can insert them using
the comma operator: `"abc", 0FFH, "def"`.

Source files are read as UTF-8. Source encoding is independent of emitted object
bytes: `db`, `mz_asc`, `mz_disp` and other data instructions retain their existing
byte-generation rules.

The assembler uses 32-bit integer math internally. When a 8-bit or 16-bit value
is generated, the excessive bits are usually truncated. Except for addresses,
used in jumps, calls and indirect loads, they generate an error. Index and
relative jump offsets are also checked to be in their allowed range.

Operators
---------

  * Member: `.`
  * Positive: `+a`
  * Negative: `-a`
  * Complement: `~a`
  * Not: `!a`
  * Multiply: `a * b`
  * Divide: `a / b`
  * Modulo: `a % b`
  * Add: `a + b`
  * Subtract: `a - b`
  * Shift left: `a << b`
  * Shift right: `a >> b`
  * Shift right unsigned: `a >>> b`
  * Less than: `a < b`
  * Less or equal: `a <= b`
  * Greater than: `a > b`
  * Greater or equal: `a >= b`
  * Equal: `a = b`
  * Not equal: `a != b`
  * Bitwise and: `a & b`
  * Bitwise xor: `a ^ b`
  * Bitwise or: `a | b`
  * Logical and: `a && b`
  * Logical or: `a || b`
  * Ternary if: `a ? b : c`
  * Annotation: `a b`
  * Sequence: `a, b`
  * Group: `()`
  * Index: `[]`

Logical operators use integers to represent true / false values. 0 means false,
any other value means true. They return -1 for true values.

Logical and / or apply short-circuit evaluation and evaluate to the last
evaluated value, so they can also be used similar to a ternary operator.

Expressions can span multiple lines when they’re incomplete at the line ends.

Operator precedence:

  1. `.`
  2. unary `+` `-` `~` `!`
  3. `*` `/` `%`
  4. `+` `-`
  5. `<<` `>>` `>>>`
  6. `<` `<=` `>` `>=`
  7. `=` `!=`
  8. `&`
  9. `^`
  10. `|`
  11. `&&`
  12. `||`
  13. `?:`
  14. ` `
  15. `,`

Development information
-----------------------

MZGlass is free and open source software. If you want to contribute to the project
you are very welcome to. Please contact me at any one of the places mentioned in
the project information section.

You are also free to re-use code for your own projects, provided you abide by
the license terms.

IDE assembly policy and emission metadata
----------------------------------------

`AssemblyRequest(source, includes, overrides, true)` opts into ignoring any
case-insensitive `mzf_*` token in directive position. Arguments are discarded by
the parser before expression/string parsing, including malformed values and
unknown suffixes. Valid labels, source coordinates and ordinary diagnostics are
preserved across main files, includes, conditionals and macro copies. The default
three-argument request and command-line assembler retain their existing behavior.
The IDE policy is per request/parser; it does not change global assembler state.

`AssemblyResult.emissions()` describes raw payload offsets/lengths, logical
addresses, source ranges and `DATA` versus `ORIGIN_PADDING`. Offsets exclude any
MZF header even when the returned object uses that container. Metadata is produced
by the same output traversal as the bytes and retains mappings through PHASE,
conditionals, included/macro-expanded lines and reserved sections. Virtual DS
sections contribute no emitted coverage. CLI byte output and origin-fill defaults
are unchanged.

`Assembler.encodeMzfObject(payload, title, comments, load, run)` shares the existing
MZF encoder with callers supplying their own metadata. It validates word fields,
uses the MZ-700 character table and records payload-only size. IDE clients assemble
raw BIN with the opt-in directive policy, apply their project validations and call
this encoder with filtered/truncated project text. Ordinary CLI MZF assembly still
uses source metadata. `IdeAssemblyTest` covers policy isolation and emission maps;
`OriginLayoutTest` retains the ORG/PHASE and CLI/API parity checks.
