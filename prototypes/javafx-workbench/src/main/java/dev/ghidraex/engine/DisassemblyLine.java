package dev.ghidraex.engine;

public record DisassemblyLine(
        int index,
        long address,
        String bytes,
        String mnemonic,
        String operands,
        String comment,
        FlowType flowType) {

    public enum FlowType {
        NORMAL,
        CONDITIONAL,
        CALL,
        RETURN
    }

    public String formattedAddress() {
        return "0x%016x".formatted(address);
    }
}
