package dev.ghidraex.fx;

import dev.ghidraex.engine.AnalysisEngine;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DecompilerPane extends BorderPane {
    private static final Pattern TOKENS = Pattern.compile(
            "\"(?:\\\\.|[^\"\\\\])*\"|//.*|0x[0-9a-fA-F]+|\\b\\d+\\b|[A-Za-z_][A-Za-z0-9_]*|\\s+|.");
    private static final Set<String> KEYWORDS = Set.of(
            "if", "else", "switch", "case", "default", "return", "int64_t", "uint32_t", "uint16_t",
            "uint8_t", "int", "void", "struct", "while", "for");

    private final AnalysisEngine engine;
    private final Label functionName = new Label();
    private final Label signature = new Label();
    private final ListView<CodeLine> code = new ListView<>();

    DecompilerPane(AnalysisEngine engine) {
        this.engine = engine;
        getStyleClass().add("decompiler-pane");
        setTop(buildToolbar());
        code.getStyleClass().add("decompiler-list");
        code.setFixedCellSize(25);
        code.setCellFactory(ignored -> new CodeCell());
        setCenter(code);
        showSymbol("parse_packet");
    }

    void showSymbol(String symbol) {
        String safe = symbol == null || symbol.isBlank() ? "parse_packet" : symbol;
        functionName.setText(safe);
        signature.setText("int64_t  (Packet *, Session *)");
        var lines = engine.decompile(safe);
        code.setItems(FXCollections.observableArrayList(
                java.util.stream.IntStream.range(0, lines.size())
                        .mapToObj(index -> new CodeLine(index + 1, lines.get(index)))
                        .toList()));
        code.scrollTo(0);
    }

    private HBox buildToolbar() {
        Label badge = new Label("ƒ");
        badge.getStyleClass().add("function-badge");
        functionName.getStyleClass().add("location-symbol");
        signature.getStyleClass().add("location-address");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label confidence = new Label("94% confidence");
        confidence.getStyleClass().add("confidence-chip");
        Button refresh = new Button("↻");
        refresh.getStyleClass().addAll("icon-button", "navigation-button");
        refresh.setOnAction(event -> showSymbol(functionName.getText()));

        HBox bar = new HBox(9, badge, functionName, signature, spacer, confidence, refresh);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("pane-toolbar");
        return bar;
    }

    private record CodeLine(int number, String source) {
    }

    private static final class CodeCell extends ListCell<CodeLine> {
        private final Label lineNumber = new Label();
        private final TextFlow flow = new TextFlow();
        private final HBox row = new HBox(14, lineNumber, flow);

        private CodeCell() {
            getStyleClass().add("code-cell");
            lineNumber.getStyleClass().add("code-line-number");
            lineNumber.setMinWidth(40);
            lineNumber.setPrefWidth(40);
            lineNumber.setAlignment(Pos.CENTER_RIGHT);
            flow.getStyleClass().add("code-flow");
            HBox.setHgrow(flow, Priority.ALWAYS);
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(CodeLine line, boolean empty) {
            super.updateItem(line, empty);
            if (empty || line == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            lineNumber.setText(Integer.toString(line.number()));
            flow.getChildren().clear();
            Matcher matcher = TOKENS.matcher(line.source());
            while (matcher.find()) {
                String token = matcher.group();
                Text text = new Text(token);
                text.getStyleClass().add(tokenClass(token));
                flow.getChildren().add(text);
            }
            setText(null);
            setGraphic(row);
        }

        private static String tokenClass(String token) {
            if (token.startsWith("//")) return "syntax-comment";
            if (token.startsWith("\"")) return "syntax-string";
            if (token.matches("0x[0-9a-fA-F]+|\\d+")) return "syntax-number";
            if (KEYWORDS.contains(token)) return "syntax-keyword";
            if (token.matches("[A-Za-z_][A-Za-z0-9_]*")) return "syntax-identifier";
            return "syntax-plain";
        }
    }
}
