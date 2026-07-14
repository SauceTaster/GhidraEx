package dev.ghidraex.fx;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Small synchronized LRU used only for immutable, context-qualified read payloads. */
final class BoundedResultCache<K, V> {
    private final int maximumEntries;
    private final LinkedHashMap<K, V> entries = new LinkedHashMap<>(16, 0.75f, true);

    BoundedResultCache(int maximumEntries) {
        if (maximumEntries <= 0) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.maximumEntries = maximumEntries;
    }

    synchronized Optional<V> get(K key) {
        return Optional.ofNullable(entries.get(Objects.requireNonNull(key, "key")));
    }

    synchronized void put(K key, V value) {
        entries.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
        while (entries.size() > maximumEntries) {
            K eldest = entries.entrySet().iterator().next().getKey();
            entries.remove(eldest);
        }
    }

    synchronized void clear() {
        entries.clear();
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized Map<K, V> snapshot() {
        return Map.copyOf(entries);
    }
}
