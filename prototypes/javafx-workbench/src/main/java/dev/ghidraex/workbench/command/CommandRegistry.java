package dev.ghidraex.workbench.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class CommandRegistry {
    private final Map<String, WorkbenchCommand> commands = new LinkedHashMap<>();

    public void register(WorkbenchCommand command) {
        Objects.requireNonNull(command, "command");
        if (commands.putIfAbsent(command.id(), command) != null) {
            throw new IllegalArgumentException("Duplicate command id: " + command.id());
        }
    }

    public List<WorkbenchCommand> all() {
        return List.copyOf(commands.values());
    }

    public List<WorkbenchCommand> search(String query, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String normalized = Objects.requireNonNullElse(query, "").strip().toLowerCase(Locale.ROOT);
        List<RankedCommand> ranked = new ArrayList<>();
        int insertionOrder = 0;
        for (WorkbenchCommand command : commands.values()) {
            int score = rank(command, normalized);
            if (score < Integer.MAX_VALUE) {
                ranked.add(new RankedCommand(command, score, insertionOrder));
            }
            insertionOrder++;
        }
        return ranked.stream()
                .sorted(Comparator.comparingInt(RankedCommand::score)
                        .thenComparingInt(RankedCommand::insertionOrder))
                .limit(limit)
                .map(RankedCommand::command)
                .toList();
    }

    public boolean execute(String id) {
        WorkbenchCommand command = commands.get(id);
        if (command == null || !command.enabled().getAsBoolean()) {
            return false;
        }
        command.action().run();
        return true;
    }

    private static int rank(WorkbenchCommand command, String query) {
        if (query.isEmpty()) return 100;
        String title = command.title().toLowerCase(Locale.ROOT);
        String category = command.category().toLowerCase(Locale.ROOT);
        String id = command.id().toLowerCase(Locale.ROOT);
        if (title.equals(query)) return 0;
        if (title.startsWith(query)) return 10;
        int titleIndex = title.indexOf(query);
        if (titleIndex >= 0) return 20 + titleIndex;
        if (isSubsequence(query, title)) return 60;
        if (category.contains(query)) return 80;
        if (id.contains(query)) return 90;
        return Integer.MAX_VALUE;
    }

    private static boolean isSubsequence(String needle, String haystack) {
        int cursor = 0;
        for (int index = 0; index < haystack.length() && cursor < needle.length(); index++) {
            if (haystack.charAt(index) == needle.charAt(cursor)) {
                cursor++;
            }
        }
        return cursor == needle.length();
    }

    private record RankedCommand(WorkbenchCommand command, int score, int insertionOrder) {
    }
}
