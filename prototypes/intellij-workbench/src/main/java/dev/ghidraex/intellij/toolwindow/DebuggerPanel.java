package dev.ghidraex.intellij.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;
import dev.ghidraex.debug.DebuggerModel;
import dev.ghidraex.intellij.session.SessionListener;
import dev.ghidraex.intellij.session.WorkbenchSession;

import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Native debugger workflow surface backed by the explicit synthetic state machine. */
final class DebuggerPanel extends JPanel {
    private final WorkbenchSession session;
    private final JBLabel status = new JBLabel();
    private final Tree stackTree = new Tree();
    private final RegisterTableModel registerModel = new RegisterTableModel();
    private final BreakpointTableModel breakpointModel;
    private final EventTableModel eventModel = new EventTableModel();

    DebuggerPanel(Project project) {
        super(new BorderLayout());
        this.session = WorkbenchSession.getInstance(project);
        this.breakpointModel = new BreakpointTableModel(session);

        status.setBorder(JBUI.Borders.empty(8, 10));
        add(status, BorderLayout.NORTH);

        var registerTable = table(registerModel);
        registerTable.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(70));
        registerTable.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(150));

        stackTree.setRootVisible(true);
        stackTree.setShowsRootHandles(true);

        var stateSplit = new JBSplitter(false, 0.52f);
        stateSplit.setFirstComponent(wrapped("Threads & Frames", new JBScrollPane(stackTree)));
        stateSplit.setSecondComponent(wrapped("Registers", new JBScrollPane(registerTable)));

        var breakpointTable = table(breakpointModel);
        breakpointTable.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(55));
        breakpointTable.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(105));
        breakpointTable.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(180));

        var eventTable = table(eventModel);
        eventTable.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(55));
        eventTable.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(120));
        eventTable.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(440));

        var details = new JBTabbedPane();
        details.addTab("Breakpoints", new JBScrollPane(breakpointTable));
        details.addTab("Debugger Events", new JBScrollPane(eventTable));

        var mainSplit = new JBSplitter(true, 0.52f);
        mainSplit.setFirstComponent(stateSplit);
        mainSplit.setSecondComponent(details);
        add(mainSplit, BorderLayout.CENTER);

        update(session.debugger());
        project.getMessageBus().connect(project).subscribe(
                SessionListener.TOPIC,
                changed -> update(changed.debugger())
        );
    }

    private void update(DebuggerModel.Snapshot snapshot) {
        status.setText("<html><b>SYNTHETIC DEBUG ADAPTER</b> &nbsp; "
                + snapshot.state() + " · " + snapshot.stopReason()
                + " &nbsp; RIP <code>" + hex(snapshot.programCounter()) + "</code>"
                + " &nbsp; <i>No Ghidra target or Trace/RMI connection is active.</i></html>");

        var root = new DefaultMutableTreeNode("orbit-controller.bin [synthetic process]");
        var thread = new DefaultMutableTreeNode(snapshot.activeThread() + " — "
                + snapshot.state().name().toLowerCase(Locale.ROOT));
        root.add(thread);
        for (DebuggerModel.StackFrame frame : snapshot.frames()) {
            thread.add(new DefaultMutableTreeNode(
                    "#" + frame.level() + "  " + frame.function() + " @ " + hex(frame.address())
            ));
        }
        stackTree.setModel(new DefaultTreeModel(root));
        TreeUtil.expandAll(stackTree);

        registerModel.replace(snapshot.registers());
        breakpointModel.replace(snapshot.breakpoints());
        eventModel.replace(snapshot.events());
    }

    private static JBTable table(AbstractTableModel model) {
        var table = new JBTable(model);
        table.setShowGrid(false);
        table.setStriped(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        return table;
    }

    private static JPanel wrapped(String title, JBScrollPane content) {
        var panel = new JPanel(new BorderLayout());
        var label = new JBLabel(title);
        label.setBorder(JBUI.Borders.empty(5, 8));
        panel.add(label, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private static String hex(long value) {
        return String.format(Locale.ROOT, "0x%016x", value);
    }

    private static final class RegisterTableModel extends AbstractTableModel {
        private List<Map.Entry<String, Long>> registers = List.of();

        void replace(Map<String, Long> replacement) {
            registers = new ArrayList<>(replacement.entrySet());
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return registers.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Register" : "Value";
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            var register = registers.get(rowIndex);
            return columnIndex == 0 ? register.getKey() : hex(register.getValue());
        }
    }

    private static final class BreakpointTableModel extends AbstractTableModel {
        private final WorkbenchSession session;
        private List<DebuggerModel.Breakpoint> breakpoints = List.of();

        private BreakpointTableModel(WorkbenchSession session) {
            this.session = session;
        }

        void replace(List<DebuggerModel.Breakpoint> replacement) {
            breakpoints = List.copyOf(replacement);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return breakpoints.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Enabled";
                case 1 -> "Address";
                case 2 -> "Symbol";
                case 3 -> "Hits";
                default -> throw new IndexOutOfBoundsException(column);
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return switch (columnIndex) {
                case 0 -> Boolean.class;
                case 3 -> Integer.class;
                default -> String.class;
            };
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex == 0 && value instanceof Boolean enabled) {
                session.setBreakpointEnabled(breakpoints.get(rowIndex).address(), enabled);
            }
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            var breakpoint = breakpoints.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> breakpoint.enabled();
                case 1 -> hex(breakpoint.address());
                case 2 -> breakpoint.symbol();
                case 3 -> breakpoint.hitCount();
                default -> throw new IndexOutOfBoundsException(columnIndex);
            };
        }
    }

    private static final class EventTableModel extends AbstractTableModel {
        private List<DebuggerModel.Event> events = List.of();

        void replace(List<DebuggerModel.Event> replacement) {
            events = List.copyOf(replacement).reversed();
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return events.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "#";
                case 1 -> "Command";
                case 2 -> "Detail";
                default -> throw new IndexOutOfBoundsException(column);
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Long.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            var event = events.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> event.sequence();
                case 1 -> event.command();
                case 2 -> event.detail();
                default -> throw new IndexOutOfBoundsException(columnIndex);
            };
        }
    }
}
