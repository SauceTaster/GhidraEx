package dev.ghidraex.intellij.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import dev.ghidraex.backend.BackendProbe;
import dev.ghidraex.backend.PluginIntegrationCatalog;
import dev.ghidraex.backend.RuntimeCapabilityCatalog;
import dev.ghidraex.intellij.session.SessionListener;
import dev.ghidraex.intellij.session.WorkbenchSession;
import dev.ghidraex.intellij.read.ReadModels;
import dev.ghidraex.viewstate.ContextualReadSlot;

import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.util.List;
import java.util.Locale;

/** Runtime truth surface: active provider, detected Ghidra install, and extension lifecycle. */
final class RuntimePanel extends JPanel {
    private final JBLabel status = new JBLabel();
    private final JBTextArea details = new JBTextArea();
    private final CapabilityTableModel capabilities = new CapabilityTableModel();
    private final IntegrationTableModel integrations = new IntegrationTableModel();

    RuntimePanel(Project project) {
        super(new BorderLayout());
        status.setBorder(JBUI.Borders.empty(8, 10));
        add(status, BorderLayout.NORTH);

        var capabilityTable = table(capabilities);
        capabilityTable.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(230));
        capabilityTable.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(165));
        capabilityTable.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(150));
        capabilityTable.getColumnModel().getColumn(3).setPreferredWidth(JBUI.scale(420));

        var integrationTable = table(integrations);
        integrationTable.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(90));
        integrationTable.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(270));
        integrationTable.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(135));
        integrationTable.getColumnModel().getColumn(3).setPreferredWidth(JBUI.scale(430));

        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setRows(6);
        details.setBorder(JBUI.Borders.empty(8));

        var tabs = new JBTabbedPane();
        tabs.addTab("Capability Matrix", new JBScrollPane(capabilityTable));
        tabs.addTab("Plugin & Extension Boundaries", new JBScrollPane(integrationTable));
        add(tabs, BorderLayout.CENTER);
        add(new JBScrollPane(details), BorderLayout.SOUTH);

        var session = WorkbenchSession.getInstance(project);
        update(session.runtimeRead());
        project.getMessageBus().connect(project).subscribe(
                SessionListener.TOPIC,
                changed -> update(changed.runtimeRead())
        );
    }

    private void update(
            ContextualReadSlot.Snapshot<ReadModels.RuntimeQuery, ReadModels.RuntimeDetails> snapshot) {
        if (snapshot.displayed() == null) {
            status.setText("<html><b>" + snapshot.freshness() + "</b> &nbsp; generation "
                    + snapshot.context().contentGeneration() + " &nbsp; " + snapshot.detail() + "</html>");
            details.setText(snapshot.detail());
            capabilities.replace(List.of());
            integrations.replace(List.of());
            return;
        }
        ReadModels.RuntimeDetails runtime = snapshot.displayed().value();
        BackendProbe.Status backend = runtime.backend();
        String discovery = backend.realBackendAvailable()
                ? "GHIDRA " + backend.ghidraVersion() + " DETECTED — NOT CONNECTED"
                : backend.discoveryState().name().replace('_', ' ');
        status.setText("<html><b>" + snapshot.freshness() + " · ACTIVE: "
                + backend.activeBackend() + "</b> &nbsp; | &nbsp; "
                + discovery + "</html>");
        details.setText("Configured by: " + backend.configuredBy() + System.lineSeparator()
                + "Ghidra home: " + display(backend.ghidraHome()) + System.lineSeparator()
                + "Headless launcher: " + display(backend.headlessLauncher()) + System.lineSeparator()
                + "Discovery state: " + backend.discoveryState().name().toLowerCase(Locale.ROOT)
                + System.lineSeparator() + System.lineSeparator()
                + backend.detail());
        details.setCaretPosition(0);
        capabilities.replace(runtime.capabilities());
        integrations.replace(runtime.integrations());
    }

    private static String display(Object value) {
        return value == null ? "not configured" : value.toString();
    }

    private static JBTable table(AbstractTableModel model) {
        var table = new JBTable(model);
        table.setShowGrid(false);
        table.setStriped(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JBTable.AUTO_RESIZE_LAST_COLUMN);
        return table;
    }

    private static final class CapabilityTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Surface", "Provider", "State", "Detail"};
        private List<RuntimeCapabilityCatalog.Capability> rows = List.of();

        void replace(List<RuntimeCapabilityCatalog.Capability> replacement) {
            rows = List.copyOf(replacement);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            var row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.surface();
                case 1 -> row.provider();
                case 2 -> row.availability();
                case 3 -> row.detail();
                default -> throw new IndexOutOfBoundsException(columnIndex);
            };
        }
    }

    private static final class IntegrationTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Host", "Extension point", "State", "Lifecycle"};
        private List<PluginIntegrationCatalog.Integration> rows = List.of();

        void replace(List<PluginIntegrationCatalog.Integration> replacement) {
            rows = List.copyOf(replacement);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            var row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.host();
                case 1 -> row.extensionPoint();
                case 2 -> row.state();
                case 3 -> row.lifecycle();
                default -> throw new IndexOutOfBoundsException(columnIndex);
            };
        }
    }
}
