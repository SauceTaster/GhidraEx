import * as vscode from 'vscode';
import {
  CancelledError,
  type AnalysisProgress,
  type SyntheticEngine,
} from '../core/engine';

export type AnalysisLifecycle = 'idle' | 'queued' | 'running' | 'cancelling' | 'cancelled' | 'failed' | 'completed';

export interface NativeAnalysisState {
  readonly operationId?: string;
  readonly lifecycle: AnalysisLifecycle;
  readonly percent: number;
  readonly phase: string;
  readonly detail: string;
}

export class NativeAnalysisCoordinator implements vscode.Disposable {
  private readonly stateEmitter = new vscode.EventEmitter<NativeAnalysisState>();
  private active: { readonly operationId: string; readonly controller: AbortController } | undefined;
  private sequence = 0;
  private disposed = false;
  private currentState: NativeAnalysisState = {
    lifecycle: 'idle',
    percent: 0,
    phase: 'Ready',
    detail: 'No analysis is running.',
  };

  public readonly onDidChangeState = this.stateEmitter.event;

  public constructor(
    private readonly engine: SyntheticEngine,
    private readonly status: vscode.StatusBarItem,
    private readonly output: vscode.LogOutputChannel,
  ) {}

  public get state(): NativeAnalysisState {
    return this.currentState;
  }

  public async start(): Promise<AnalysisLifecycle> {
    if (this.disposed) return 'cancelled';
    // Keep one workspace operation authoritative. CodeLens and Command Palette
    // can still invoke Start while the contributed UI hides that action, so a
    // second request must not abort/replace the active operation and let stale
    // completion handlers corrupt its progress/context state.
    if (this.active !== undefined) {
      this.output.warn(`[${this.active.operationId}] ignored duplicate start request`);
      return this.currentState.lifecycle;
    }
    const operationId = `analysis-${Date.now().toString(36)}-${++this.sequence}`;
    const controller = new AbortController();
    this.active = { operationId, controller };
    this.publish({ operationId, lifecycle: 'queued', percent: 0, phase: 'Queued', detail: 'Preparing analyzers.' });
    this.output.info(`[${operationId}] queued for orbit-controller-v1 revision 17`);

    try {
      await vscode.commands.executeCommand('setContext', 'ghidraex.analysisRunning', true);
      if (this.disposed) return 'cancelled';
      return await vscode.window.withProgress({
        location: vscode.ProgressLocation.Notification,
        title: 'GhidraEx: analyzing orbit-controller-v1',
        cancellable: true,
      }, async (progress, token) => {
        let previousPercent = 0;
        const cancellation = token.onCancellationRequested(() => this.cancel(operationId));
        try {
          await this.engine.runAnalysis(controller.signal, update => {
            if (this.active?.operationId !== operationId || controller.signal.aborted) return;
            this.publishProgress(operationId, update);
            progress.report({
              increment: Math.max(0, update.percent - previousPercent),
              message: `${update.phase} · ${update.percent}%`,
            });
            previousPercent = update.percent;
          });
          if (this.active?.operationId !== operationId) return 'cancelled';
          this.publish({
            operationId,
            lifecycle: 'completed',
            percent: 100,
            phase: 'Complete',
            detail: 'All selected analyzers completed.',
          });
          this.output.info(`[${operationId}] completed`);
          return 'completed';
        } finally {
          cancellation.dispose();
        }
      });
    } catch (error) {
      if (this.disposed) return 'cancelled';
      if (error instanceof CancelledError || controller.signal.aborted) {
        this.publish({
          operationId,
          lifecycle: 'cancelled',
          percent: this.currentState.percent,
          phase: 'Cancelled',
          detail: 'Cancellation acknowledged; partial analysis remains valid.',
        });
        this.output.warn(`[${operationId}] cancelled at ${this.currentState.percent}%`);
        return 'cancelled';
      }
      const detail = error instanceof Error ? error.message : 'Unknown analysis failure.';
      this.publish({
        operationId,
        lifecycle: 'failed',
        percent: this.currentState.percent,
        phase: 'Failed',
        detail,
      });
      this.output.error(`[${operationId}] ${detail}`);
      return 'failed';
    } finally {
      if (this.active?.operationId === operationId) this.active = undefined;
      if (!this.disposed) {
        await vscode.commands.executeCommand('setContext', 'ghidraex.analysisRunning', false);
        this.status.command = 'ghidraex.openWorkbench';
        if (this.currentState.lifecycle !== 'completed' && this.currentState.lifecycle !== 'cancelled' && this.currentState.lifecycle !== 'failed') {
          this.publish({ lifecycle: 'idle', percent: 0, phase: 'Ready', detail: 'No analysis is running.' });
        }
      }
    }
  }

  public cancel(operationId?: string): void {
    if (this.disposed) return;
    if (this.active === undefined || (operationId !== undefined && operationId !== this.active.operationId)) return;
    const active = this.active;
    this.publish({
      operationId: active.operationId,
      lifecycle: 'cancelling',
      percent: this.currentState.percent,
      phase: this.currentState.phase,
      detail: 'Waiting for the current analyzer checkpoint.',
    });
    this.output.info(`[${active.operationId}] cancellation requested`);
    active.controller.abort();
  }

  private publishProgress(operationId: string, progress: AnalysisProgress): void {
    this.publish({ operationId, lifecycle: 'running', ...progress });
  }

  private publish(state: NativeAnalysisState): void {
    if (this.disposed) return;
    this.currentState = state;
    if (state.lifecycle === 'running' || state.lifecycle === 'queued' || state.lifecycle === 'cancelling') {
      this.status.text = `$(sync~spin) GhidraEx ${state.percent}%`;
      this.status.command = 'ghidraex.cancelAnalysis';
    } else if (state.lifecycle === 'failed') {
      this.status.text = '$(error) GhidraEx analysis failed';
    } else {
      this.status.text = '$(debug-alt) GhidraEx';
    }
    this.status.tooltip = `${state.phase}: ${state.detail}`;
    this.stateEmitter.fire(state);
  }

  public dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.active?.controller.abort();
    this.active = undefined;
    // Context keys are window-scoped, not lifetime-scoped to this object. Clear
    // the busy key explicitly so disable/re-enable cannot inherit a phantom run.
    void vscode.commands.executeCommand('setContext', 'ghidraex.analysisRunning', false);
    this.stateEmitter.dispose();
  }
}
