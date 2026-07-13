package dev.ghidraex.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolSearchTest {
    @Test
    void exactAndPrefixMatchesRankAheadOfContainsMatches() {
        try (var engine = new SyntheticAnalysisEngine()) {
            var exact = engine.searchSymbols("parse_packet", 10);
            assertEquals("parse_packet", exact.getFirst().name());

            var prefix = engine.searchSymbols("session_", 10);
            assertTrue(prefix.get(0).name().startsWith("session_"));
            assertTrue(prefix.get(1).name().startsWith("session_"));
            assertEquals("derive_session_key", prefix.getLast().name());
        }
    }

    @Test
    void searchAcceptsAddressesAndHonorsLimit() {
        try (var engine = new SyntheticAnalysisEngine()) {
            Symbol symbol = engine.searchSymbols("parse_packet", 1).getFirst();
            String suffix = symbol.formattedAddress().substring(8);
            var results = engine.searchSymbols(suffix, 1);

            assertEquals(1, results.size());
            assertEquals(symbol, results.getFirst());
            assertTrue(engine.searchSymbols("", 0).isEmpty());
        }
    }
}
