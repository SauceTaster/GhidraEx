package ex.ghidra.web;

import java.util.List;

/** Tiny dependency-free encoder for the prototype's fixed response DTOs. */
final class WorkbenchJson {
    private WorkbenchJson() {}

    static String snapshot(SyntheticEngine.WorkbenchSnapshot snapshot) {
        StringBuilder json = new StringBuilder(16_384);
        json.append('{')
                .append("\"apiVersion\":").append(snapshot.apiVersion()).append(',')
                .append("\"project\":");
        project(json, snapshot.project());
        json.append(',').append("\"backend\":");
        backend(json, snapshot.backend());
        json.append(',').append("\"analysis\":");
        analysis(json, snapshot.analysis());
        json.append(',').append("\"symbols\":");
        symbols(json, snapshot.symbols());
        json.append(',').append("\"listingInfo\":");
        listingExtent(json, snapshot.listingInfo());
        json.append(',').append("\"listing\":");
        listing(json, snapshot.listing());
        json.append(',').append("\"decompiler\":").append(quote(snapshot.decompiler()))
                .append(',').append("\"inspector\":");
        inspector(json, snapshot.inspector());
        return json.append('}').toString();
    }

    static String analysis(SyntheticEngine.AnalysisState state) {
        StringBuilder json = new StringBuilder(192);
        analysis(json, state);
        return json.toString();
    }

    static String symbolSearch(String query, List<SyntheticEngine.SymbolRecord> records) {
        StringBuilder json = new StringBuilder(2_048);
        json.append("{\"apiVersion\":1,\"query\":").append(quote(query)).append(",\"symbols\":");
        symbols(json, records);
        return json.append('}').toString();
    }

    static String listingWindow(SyntheticEngine.ListingWindow window) {
        StringBuilder json = new StringBuilder(6_144);
        json.append("{\"apiVersion\":").append(window.apiVersion())
                .append(",\"requestedAddress\":").append(quote(window.requestedAddress()))
                .append(",\"totalInstructions\":").append(window.totalInstructions())
                .append(",\"minAddress\":").append(quote(window.minAddress()))
                .append(",\"maxAddress\":").append(quote(window.maxAddress()))
                .append(",\"rows\":");
        listing(json, window.rows());
        return json.append('}').toString();
    }

    static String error(String code, String message) {
        return "{\"error\":{\"code\":" + quote(code) + ",\"message\":" + quote(message) + "}}";
    }

    static String quote(String value) {
        if (value == null) return "null";
        StringBuilder result = new StringBuilder(value.length() + 12).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) result.append("\\u%04x".formatted((int) character));
                    else result.append(character);
                }
            }
        }
        return result.append('"').toString();
    }

    private static void project(StringBuilder json, SyntheticEngine.ProjectState project) {
        json.append('{')
                .append("\"name\":").append(quote(project.name())).append(',')
                .append("\"binary\":").append(quote(project.binary())).append(',')
                .append("\"format\":").append(quote(project.format())).append(',')
                .append("\"architecture\":").append(quote(project.architecture())).append(',')
                .append("\"imageBase\":").append(quote(project.imageBase())).append(',')
                .append("\"sha256\":").append(quote(project.sha256()))
                .append('}');
    }

    private static void backend(StringBuilder json, SyntheticEngine.BackendState backend) {
        json.append('{')
                .append("\"mode\":").append(quote(backend.mode())).append(',')
                .append("\"health\":").append(quote(backend.health())).append(',')
                .append("\"version\":").append(quote(backend.version())).append(',')
                .append("\"launcher\":").append(quote(backend.launcher())).append(',')
                .append("\"capabilities\":[");
        for (int index = 0; index < backend.capabilities().size(); index++) {
            if (index > 0) json.append(',');
            json.append(quote(backend.capabilities().get(index)));
        }
        json.append("]}");
    }

    private static void analysis(StringBuilder json, SyntheticEngine.AnalysisState state) {
        json.append('{')
                .append("\"status\":").append(quote(state.status())).append(',')
                .append("\"progress\":").append(state.progress()).append(',')
                .append("\"phase\":").append(quote(state.phase())).append(',')
                .append("\"elapsedMs\":").append(state.elapsedMs()).append(',')
                .append("\"functionsDiscovered\":").append(state.functionsDiscovered())
                .append('}');
    }

    private static void symbols(StringBuilder json, List<SyntheticEngine.SymbolRecord> records) {
        json.append('[');
        for (int index = 0; index < records.size(); index++) {
            if (index > 0) json.append(',');
            SyntheticEngine.SymbolRecord symbol = records.get(index);
            json.append('{')
                    .append("\"name\":").append(quote(symbol.name())).append(',')
                    .append("\"address\":").append(quote(symbol.address())).append(',')
                    .append("\"namespace\":").append(quote(symbol.namespace())).append(',')
                    .append("\"kind\":").append(quote(symbol.kind())).append(',')
                    .append("\"refs\":").append(symbol.refs()).append(',')
                    .append("\"confidence\":").append(symbol.confidence())
                    .append('}');
        }
        json.append(']');
    }

    private static void listingExtent(StringBuilder json, SyntheticEngine.ListingExtent extent) {
        json.append('{')
                .append("\"totalInstructions\":").append(extent.totalInstructions()).append(',')
                .append("\"minAddress\":").append(quote(extent.minAddress())).append(',')
                .append("\"maxAddress\":").append(quote(extent.maxAddress())).append(',')
                .append("\"viewportRows\":").append(extent.viewportRows())
                .append('}');
    }

    private static void listing(StringBuilder json, List<SyntheticEngine.ListingRow> rows) {
        json.append('[');
        for (int index = 0; index < rows.size(); index++) {
            if (index > 0) json.append(',');
            SyntheticEngine.ListingRow row = rows.get(index);
            json.append('{')
                    .append("\"address\":").append(quote(row.address())).append(',')
                    .append("\"bytes\":").append(quote(row.bytes())).append(',')
                    .append("\"mnemonic\":").append(quote(row.mnemonic())).append(',')
                    .append("\"operands\":").append(quote(row.operands())).append(',')
                    .append("\"annotation\":").append(quote(row.annotation())).append(',')
                    .append("\"flow\":").append(quote(row.flow()))
                    .append('}');
        }
        json.append(']');
    }

    private static void inspector(StringBuilder json, SyntheticEngine.InspectorState inspector) {
        json.append('{')
                .append("\"address\":").append(quote(inspector.address())).append(',')
                .append("\"functionName\":").append(quote(inspector.functionName())).append(',')
                .append("\"signature\":").append(quote(inspector.signature())).append(',')
                .append("\"segment\":").append(quote(inspector.segment())).append(',')
                .append("\"offset\":").append(quote(inspector.offset())).append(',')
                .append("\"xrefsIn\":").append(inspector.xrefsIn()).append(',')
                .append("\"xrefsOut\":").append(inspector.xrefsOut()).append(',')
                .append("\"stackDelta\":").append(inspector.stackDelta()).append(',')
                .append("\"prototypeSource\":").append(quote(inspector.prototypeSource()))
                .append('}');
    }
}
