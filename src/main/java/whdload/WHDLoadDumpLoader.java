package whdload;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import ghidra.app.util.Option;
import ghidra.app.util.bin.BinaryReader;
import ghidra.app.util.bin.ByteArrayProvider;
import ghidra.app.util.bin.ByteProvider;
import ghidra.app.util.importer.MessageLog;
import ghidra.app.util.opinion.AbstractLibrarySupportLoader;
import ghidra.app.util.opinion.LoadSpec;
import ghidra.program.model.address.Address;
import ghidra.program.flatapi.FlatProgramAPI;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeConflictHandler;
import ghidra.program.model.data.DataUtilities;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.TerminatedStringDataType;
import ghidra.program.model.data.VoidDataType;
import ghidra.program.model.data.DataUtilities.ClearDataMode;
import ghidra.program.model.lang.LanguageCompilerSpecPair;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Parameter;
import ghidra.program.model.listing.ParameterImpl;
import ghidra.program.model.listing.Program;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.util.CodeUnitInsertionException;
import ghidra.util.exception.CancelledException;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;
import ghidra.util.task.TaskMonitor;
import structs.CustomChipRegisters;
import structs.ResidentLoader;
import structs.WHDLoadSlave;

public class WHDLoadDumpLoader extends AbstractLibrarySupportLoader {

    @Override
    public String getName() {
        return "Amiga WHDLoad Dump File";
    }

    @Override
    public Collection<LoadSpec> findSupportedLoadSpecs(ByteProvider provider) throws IOException {
        List<LoadSpec> loadSpecs = new ArrayList<>();

        if (WHDLoadDumpFile.isDumpFile(new BinaryReader(provider, false))) {
            loadSpecs.add(new LoadSpec(this, 0, new LanguageCompilerSpecPair("68000:BE:32:default", "default"), true));
        }

        return loadSpecs;
    }

    @Override
    protected void load(ByteProvider provider, LoadSpec loadSpec, List<Option> options, Program program,
            TaskMonitor monitor, MessageLog log) throws CancelledException, IOException {
        FlatProgramAPI fpa = new FlatProgramAPI(program);
        Memory mem = program.getMemory();
        BinaryReader reader = new BinaryReader(provider, false);

        WHDLoadDumpFile dumpFile = new WHDLoadDumpFile(reader, monitor, log);
        log.appendMsg("Read the WHDLoad dump file");

        if (dumpFile.baseMem != null) {
            log.appendMsg("Creating base memory block");
            this.createMemoryBlock("BaseMem", dumpFile.baseMem, fpa, log);
        }
        if (dumpFile.expMem != null) {
            log.appendMsg("Creating expansion memory block");
            this.createMemoryBlock("ExpMem", dumpFile.expMem, fpa, log);
        }
        if (dumpFile.helper != null) {
            log.appendMsg("Creating helper program memory block");
            String name = dumpFile.helperName != null ? dumpFile.helperName : "Helper";
            this.createMemoryBlock(name, dumpFile.helper, fpa, log);
        }
        // if (dumpFile.resLoad != null) {
        // log.appendMsg("Creating ResidentLoader memory block");
        // this.createMemoryBlock("ResLoad", dumpFile.resLoad, fpa, log);
        // }
        if (dumpFile.customChips != null) {
            log.appendMsg("Creating custom chips memory block");
            this.createMemoryBlock("CustomChips", dumpFile.customChips, fpa, log);
            try {
                DataType regs = program.getDataTypeManager().addDataType(new CustomChipRegisters().toDataType(),
                        DataTypeConflictHandler.DEFAULT_HANDLER);
                DataUtilities.createData(program, fpa.toAddr(dumpFile.customChips.start), regs, -1, false,
                        ClearDataMode.CLEAR_ALL_UNDEFINED_CONFLICT_DATA);
                fpa.createLabel(fpa.toAddr(dumpFile.customChips.start), "CustomChips", false);
            } catch (Exception e) {
                log.appendException(e);
            }

        }

        // If we have the content of the helper program then we can do some
        // further analysis to annotate the header and the entry point.
        if (dumpFile.helper != null && dumpFile.helper.content != null) {
            ByteProvider headerProvider = new ByteArrayProvider(dumpFile.helper.content);
            WHDLoadHelperHeader header = new WHDLoadHelperHeader(headerProvider);
            log.appendMsg(String.format("This helper targets WHDLoad v%d", header.whdloadVersion));
            try {
                DataType headerType = program.getDataTypeManager().addDataType(
                        (new WHDLoadSlave(header.whdloadVersion)).toDataType(),
                        DataTypeConflictHandler.DEFAULT_HANDLER);
                DataUtilities.createData(program, fpa.toAddr(dumpFile.helper.start), headerType, -1, false,
                        ClearDataMode.CLEAR_ALL_UNDEFINED_CONFLICT_DATA);
            } catch (DuplicateNameException | IOException | CodeUnitInsertionException e) {
                log.appendException(e);
            }

            // We can also add type annotations to the referents of the various
            // relative pointers in the header, if they are set.
            DataType cString = TerminatedStringDataType.dataType;
            long base = dumpFile.helper.start;
            this.annotateHeaderRPtr(base, header.currentDirOffset, cString, program, log);
            this.annotateHeaderRPtr(base, header.dontCacheOffset, cString, program, log);
            this.annotateHeaderRPtr(base, header.nameOffset, cString, program, log);
            this.annotateHeaderRPtr(base, header.copyOffset, cString, program, log);
            this.annotateHeaderRPtr(base, header.infoOffset, cString, program, log);
            this.annotateHeaderRPtr(base, header.kickNameOffset, cString, program, log);
            this.annotateHeaderRPtr(base, header.configOffset, cString, program, log);

            try {
                DataType residentLoader = new ResidentLoader(header.whdloadVersion).toDataType();
                DataType residentLoaderPtr = new PointerDataType(residentLoader);
                program.getDataTypeManager().addDataType(residentLoader, DataTypeConflictHandler.DEFAULT_HANDLER);
                program.getDataTypeManager().addDataType(residentLoaderPtr, DataTypeConflictHandler.DEFAULT_HANDLER);
                if (header.gameLoaderOffset != 0) {
                    Address addr = fpa.toAddr(base + header.gameLoaderOffset);
                    Function func = fpa.createFunction(addr, "start");
                    fpa.addEntryPoint(addr);
                    fpa.disassemble(addr);
                    func.setNoReturn(true); // program should exit with resload_Abort, not by returning
                    func.setReturnType(VoidDataType.dataType, SourceType.ANALYSIS);
                    func.setCustomVariableStorage(true);
                    Register a0 = program.getRegister("A0");
                    func.addParameter(new ParameterImpl("resload", residentLoaderPtr, a0, program),
                            SourceType.ANALYSIS);
                }
            } catch (DuplicateNameException | IOException | InvalidInputException e) {
                log.appendException(e);
            }

        }
    }

    private MemoryBlock createMemoryBlock(String name, WHDLoadDumpFile.MemoryRegion spec, FlatProgramAPI fpa,
            MessageLog log) {

        InputStream stream = null;
        if (spec.content != null) {
            log.appendMsg(String.format("%s block has a memory image of %d bytes", name, spec.content.length));
            stream = new ByteArrayInputStream(spec.content);
        } else {
            log.appendMsg(String.format("%s block has unknown content", name));
        }

        try {
            Program program = fpa.getCurrentProgram();
            int transId = program.startTransaction(
                    String.format("Create %s block at 0x%08x (%d bytes)", name, spec.start, spec.length));
            MemoryBlock block = fpa.createMemoryBlock(name, fpa.toAddr(spec.start), stream, spec.length, false);
            program.endTransaction(transId, true);

            block.setExecute(true);
            block.setRead(true);
            block.setWrite(true);

            return block;
        } catch (Exception e) {
            log.appendException(e);
            return null;
        }
    }

    private void annotateHeaderRPtr(long base, short offset, DataType type, Program program, MessageLog log) {
        if (offset == 0) {
            return; // nothing to do for an unset rptr
        }
        FlatProgramAPI fpa = new FlatProgramAPI(program);
        try {
            Address addr = fpa.toAddr(base + offset);
            DataUtilities.createData(program, addr, type, -1, false, ClearDataMode.CLEAR_ALL_UNDEFINED_CONFLICT_DATA);
        } catch (CodeUnitInsertionException e) {
            log.appendException(e);
        }
    }

}
