import * as vscode from 'vscode';
import { NativeReadCancelledError } from '../core/readService';

/** Owns AbortControllers so provider disposal cancels every transport read still in flight. */
export class NativeReadCancellationPool implements vscode.Disposable {
  private readonly controllers = new Set<AbortController>();
  private disposed = false;

  public async run<T>(
    token: vscode.CancellationToken | undefined,
    action: (signal: AbortSignal) => Promise<T>,
  ): Promise<T> {
    if (this.disposed) throw new vscode.CancellationError();
    const controller = new AbortController();
    this.controllers.add(controller);
    const candidate = token as (Partial<vscode.CancellationToken> & { isCancellationRequested?: boolean }) | undefined;
    if (candidate?.isCancellationRequested === true) controller.abort();
    const subscribe = candidate?.onCancellationRequested;
    const subscription = typeof subscribe === 'function'
      ? subscribe.call(token, () => controller.abort())
      : undefined;
    try {
      return await action(controller.signal);
    } catch (error) {
      if (error instanceof NativeReadCancelledError || controller.signal.aborted || this.disposed) {
        throw new vscode.CancellationError();
      }
      throw error;
    } finally {
      subscription?.dispose();
      this.controllers.delete(controller);
    }
  }

  /** Cancel every current operation while keeping the pool available for later reads. */
  public cancel(): void {
    for (const controller of this.controllers) controller.abort();
    this.controllers.clear();
  }

  public dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.cancel();
  }
}

/** Latest invocation wins for controller/tree reads that do not receive a VS Code token. */
export class LatestNativeRead implements vscode.Disposable {
  private sequence = 0;
  private controller: AbortController | undefined;
  private disposed = false;

  public begin(): { readonly id: number; readonly signal: AbortSignal } {
    if (this.disposed) throw new vscode.CancellationError();
    this.controller?.abort();
    this.controller = new AbortController();
    return { id: ++this.sequence, signal: this.controller.signal };
  }

  public accepts(id: number): boolean {
    return !this.disposed && id === this.sequence && this.controller?.signal.aborted === false;
  }

  public cancel(): void {
    this.controller?.abort();
    this.controller = undefined;
    this.sequence += 1;
  }

  public dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.cancel();
  }
}
