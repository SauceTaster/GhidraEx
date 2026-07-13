package dev.ghidraex.intellij.session;

import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.LightVirtualFile;
import dev.ghidraex.engine.AnalysisEngine;
import dev.ghidraex.engine.AnalysisResult;
import dev.ghidraex.engine.ProgramDescriptor;
import dev.ghidraex.engine.SymbolDescriptor;
import dev.ghidraex.engine.SyntheticAnalysisEngine;
import dev.ghidraex.intellij.editor.VirtualizedListingFile;

import java.util.List;
import java.util.Objects;

/** Project-scoped UI state. The engine itself remains free of IntelliJ dependencies. */
public final class WorkbenchSession {
    private final Project project;
    private final AnalysisEngine engine;
    private final ProgramDescriptor program;
    private final VirtualizedListingFile listingFile;
    private final LightVirtualFile decompilerFile;

    private List<SymbolDescriptor> symbols;
    private SymbolDescriptor selectedSymbol;
    private AnalysisResult lastAnalysis;

    public WorkbenchSession(Project project) {
        this.project = project;
        this.engine = SyntheticAnalysisEngine.interactive();
        this.program = engine.programs().getFirst();
        this.symbols = engine.symbols(program.id());
        this.selectedSymbol = symbols.getFirst();
        this.listingFile = new VirtualizedListingFile(program.name() + ".listing");
        this.decompilerFile = readOnlyFile(
                selectedSymbol.name() + ".decompiled.c",
                engine.decompile(program.id(), selectedSymbol.address())
        );
    }

    public static WorkbenchSession getInstance(Project project) {
        return project.getService(WorkbenchSession.class);
    }

    public AnalysisEngine engine() {
        return engine;
    }

    public ProgramDescriptor program() {
        return program;
    }

    public List<SymbolDescriptor> symbols() {
        return symbols;
    }

    public SymbolDescriptor selectedSymbol() {
        return selectedSymbol;
    }

    public AnalysisResult lastAnalysis() {
        return lastAnalysis;
    }

    public VirtualizedListingFile listingFile() {
        return listingFile;
    }

    public LightVirtualFile decompilerFile() {
        return decompilerFile;
    }

    public void selectSymbol(SymbolDescriptor symbol) {
        selectedSymbol = Objects.requireNonNull(symbol, "symbol");
        publishChange();
    }

    public void applyAnalysis(AnalysisResult result) {
        lastAnalysis = Objects.requireNonNull(result, "result");
        symbols = result.symbols();
        publishChange();
    }

    private void publishChange() {
        project.getMessageBus().syncPublisher(SessionListener.TOPIC).sessionChanged(this);
    }

    private static LightVirtualFile readOnlyFile(String name, String content) {
        var file = new LightVirtualFile(name, PlainTextFileType.INSTANCE, content);
        file.setWritable(false);
        return file;
    }
}
