package dev.ghidraex.engine;

public record ProgramDescriptor(
        String id,
        String name,
        String architecture,
        long imageBase,
        long byteSize
) {
}
