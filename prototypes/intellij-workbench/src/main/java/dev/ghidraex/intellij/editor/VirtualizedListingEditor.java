package dev.ghidraex.intellij.editor;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileEditor.FileEditorStateLevel;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import dev.ghidraex.intellij.read.LatestOnlyExecutorService;
import dev.ghidraex.intellij.session.WorkbenchSession;
import dev.ghidraex.intellij.session.SessionListener;
import dev.ghidraex.viewstate.ViewStateReducer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;

/**
 * Native IntelliJ editor for one bounded semantic listing window.
 *
 * <p>The table model owns immutable local rows only. Engine work executes on a pooled worker, and
 * the shared reducer rejects late context/request/generation results before one EDT gateway updates
 * Swing state.</p>
 */
public final class VirtualizedListingEditor extends UserDataHolderBase implements FileEditor {
    private final VirtualizedListingFile file;
    private final WorkbenchSession session;
    private final SemanticListingTableModel model = new SemanticListingTableModel();
    private final JBTable table = new JBTable(model);
    private final JBLabel freshness = new JBLabel();
    private final JPanel component = new JPanel(new BorderLayout());
    private final PropertyChangeSupport propertyChanges = new PropertyChangeSupport(this);
    private final IntelliJUiDispatcher ui;
    private final IntelliJListingProjection projection;
    private final MessageBusConnection sessionConnection;

    public VirtualizedListingEditor(Project project, VirtualizedListingFile file) {
        this.file = file;
        this.session = WorkbenchSession.getInstance(project);
        this.ui = new IntelliJUiDispatcher(project);
        var listingWorker = new LatestOnlyExecutorService("listing-editor");
        this.projection = new IntelliJListingProjection(
                session.context(),
                new SyntheticListingWindowSource(session.engine(), session.program()),
                listingWorker,
                ui,
                this::applyView,
                listingWorker::shutdownNow
        );
        this.sessionConnection = project.getMessageBus().connect();
        sessionConnection.subscribe(SessionListener.TOPIC, changed -> {
            var current = projection.snapshot();
            if (!current.context().equals(changed.context()) && !projection.disposed()) {
                boolean sameResource = ListingSemantics.sameResource(current.context(), changed.context());
                ViewStateReducer.LocationRef refreshLocation = current.location();
                projection.applySnapshot(changed.context(), current.eventSequence() + 1);
                if (sameResource && refreshLocation != null) {
                    projection.request(refreshLocation);
                }
            }
        });

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(false);
        table.setStriped(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getTableHeader().setReorderingAllowed(false);
        table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, table.getFont().getSize()));
        table.setRowHeight(JBUI.scale(22));
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(82));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(170));
        table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(135));
        table.getColumnModel().getColumn(3).setPreferredWidth(JBUI.scale(90));
        table.getColumnModel().getColumn(4).setPreferredWidth(JBUI.scale(500));

        table.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || table.getSelectedRow() < 0) {
                return;
            }
            long address = ListingSemantics.offset(
                    model.rowAt(table.getSelectedRow()).location().requestedAddress());
            session.selectSymbolAtAddress(address);
        });

        freshness.setBorder(JBUI.Borders.empty(7, 10));
        freshness.setText("EMPTY · generation " + session.context().contentGeneration()
                + " · bounded to " + IntelliJListingProjection.WINDOW_LIMIT + " rows");
        component.add(freshness, BorderLayout.NORTH);
        component.add(new JBScrollPane(table), BorderLayout.CENTER);

        if (ui.isDispatchThread()) {
            projection.request(file.initialLocation());
        } else {
            ui.dispatch(() -> {
                if (!projection.disposed()) {
                    projection.request(file.initialLocation());
                }
            });
        }
    }

    public void revealAddress(long address) {
        revealLocation(ListingSemantics.instructionLocation(address));
    }

    public void revealLocation(ViewStateReducer.LocationRef location) {
        Runnable navigate = () -> {
            if (!projection.disposed()) {
                projection.request(location);
                table.requestFocusInWindow();
            }
        };
        if (ui.isDispatchThread()) {
            navigate.run();
        } else {
            ui.dispatch(navigate);
        }
    }

    private void applyView(ViewStateReducer.ViewSnapshot view) {
        if (!ui.isDispatchThread()) {
            throw new IllegalStateException("Listing UI snapshot escaped the EDT gateway");
        }
        model.replace(view);
        ViewStateReducer.ListingWindow displayed = view.displayed();
        long generation = displayed == null
                ? projection.snapshot().context().contentGeneration()
                : displayed.context().contentGeneration();
        freshness.setText(view.freshness() + " · generation " + generation + " · "
                + view.detail() + " · bounded to " + IntelliJListingProjection.WINDOW_LIMIT + " rows");

        if (view.target() != null) {
            int row = model.indexOf(view.target());
            if (row >= 0) {
                table.setRowSelectionInterval(row, row);
                table.scrollRectToVisible(table.getCellRect(row, 0, true));
            }
        }
    }

    @Override
    public @NotNull JComponent getComponent() {
        return component;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return table;
    }

    @Override
    public @NotNull String getName() {
        return "Semantic Listing";
    }

    @Override
    public @NotNull FileEditorState getState(@NotNull FileEditorStateLevel level) {
        var snapshot = projection.snapshot();
        ViewStateReducer.LocationRef location = snapshot.location() == null
                ? file.initialLocation()
                : snapshot.location();
        return new State(snapshot.context(), location);
    }

    @Override
    public void setState(@NotNull FileEditorState state) {
        if (state instanceof State listingState && file.identifies(listingState.context())) {
            revealLocation(listingState.location());
        }
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public boolean isValid() {
        return file.isValid() && !projection.disposed();
    }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
        propertyChanges.addPropertyChangeListener(listener);
    }

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
        propertyChanges.removePropertyChangeListener(listener);
    }

    @Override
    public @NotNull VirtualFile getFile() {
        return file;
    }

    @Override
    public void dispose() {
        sessionConnection.disconnect();
        projection.close();
    }

    public record State(
            ViewStateReducer.ViewContext context,
            ViewStateReducer.LocationRef location
    ) implements FileEditorState {
        public State {
            java.util.Objects.requireNonNull(context, "context");
            java.util.Objects.requireNonNull(location, "location");
        }

        @Override
        public boolean canBeMergedWith(
                @NotNull FileEditorState otherState,
                @NotNull FileEditorStateLevel level
        ) {
            return otherState instanceof State other
                    && ListingSemantics.sameResource(context, other.context);
        }
    }

    private record IntelliJUiDispatcher(Project project) implements IntelliJListingProjection.UiAccess {
        @Override
        public void dispatch(Runnable task) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) {
                    task.run();
                }
            });
        }

        @Override
        public boolean isDispatchThread() {
            return ApplicationManager.getApplication().isDispatchThread();
        }
    }
}
