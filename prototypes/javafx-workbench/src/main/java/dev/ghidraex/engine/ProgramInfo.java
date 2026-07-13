package dev.ghidraex.engine;

public record ProgramInfo(
        String projectName,
        String binaryName,
        String architecture,
        String format,
        long imageBase,
        String sha256) {
}
