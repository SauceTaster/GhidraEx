import * as vscode from 'vscode';
import type { BackendCapabilitySnapshot } from '../core/capabilities';
import type { NativeReadService } from '../core/readService';
import { GhidraExRepl, type ReplResult } from '../core/repl';
import type { NativeScriptingSnapshot } from './advancedViews';
import { NativeReadCancellationPool } from './asyncReads';

const PROMPT = '\x1b[38;5;75mgx>\x1b[0m ';

interface ScriptTemplateQuickPickItem extends vscode.QuickPickItem {
  readonly languageId: string;
  readonly content: string;
}

export interface NativeScriptingCallbacks {
  readonly onNavigate: (row: number) => void | PromiseLike<void>;
  readonly onStartAnalysis: () => void | PromiseLike<void>;
  readonly onSnapshot: (snapshot: NativeScriptingSnapshot) => void;
  readonly onError: (error: unknown) => void;
}

interface ScriptTerminalCallbacks {
  readonly execute: (input: string) => Promise<ReplResult>;
  readonly cancel: () => void;
  readonly onResult: (result: ReplResult) => void | PromiseLike<void>;
}

export class GhidraExPseudoterminal implements vscode.Pseudoterminal, vscode.Disposable {
  private readonly writes = new vscode.EventEmitter<string>();
  private readonly closes = new vscode.EventEmitter<void>();
  private buffer = '';
  private commandHistory: string[] = [];
  private historyIndex = 0;
  private opened = false;
  private disposed = false;
  private executionGeneration = 0;
  private commandQueue: Promise<void> = Promise.resolve();

  public readonly onDidWrite = this.writes.event;
  public readonly onDidClose = this.closes.event;

  public constructor(private readonly callbacks: ScriptTerminalCallbacks) {}

  public open(): void {
    if (this.disposed) return;
    this.opened = true;
    this.writes.fire([
      '\x1b[1mGhidraEx Script Console\x1b[0m',
      'Bounded gx commands run against the deterministic fixture.',
      'Python, Java, PyGhidra, and GhidraScript require a connected real backend.',
      'Type \x1b[1mhelp\x1b[0m for commands.',
      '',
    ].join('\r\n'));
    this.writes.fire(PROMPT);
  }

  public close(): void {
    this.opened = false;
  }

  public handleInput(data: string): void {
    if (!this.opened || this.disposed) return;
    if (data.startsWith('\x1b[A')) {
      this.replaceBuffer(this.previousHistory());
      data = data.slice(3);
      if (data.length === 0) return;
    }
    if (data.startsWith('\x1b[B')) {
      this.replaceBuffer(this.nextHistory());
      data = data.slice(3);
      if (data.length === 0) return;
    }

    for (const character of data) {
      if (character === '\r' || character === '\n') {
        this.enqueueSubmission();
      } else if (character === '\x7f' || character === '\b') {
        if (this.buffer.length > 0) {
          this.buffer = this.buffer.slice(0, -1);
          this.writes.fire('\b \b');
        }
      } else if (character === '\x03') {
        this.executionGeneration += 1;
        this.callbacks.cancel();
        this.buffer = '';
        this.writes.fire('^C\r\n');
        this.writes.fire(PROMPT);
      } else if (character === '\x0c') {
        this.writes.fire('\x1b[2J\x1b[H');
        this.writes.fire(PROMPT + this.buffer);
      } else if (character >= ' ' && character !== '\x7f') {
        this.buffer += character;
        this.writes.fire(character);
      }
    }
  }

  private enqueueSubmission(): void {
    const input = this.buffer.trim();
    this.writes.fire('\r\n');
    this.buffer = '';
    if (input.length > 0) {
      if (this.commandHistory.at(-1) !== input) this.commandHistory.push(input);
      if (this.commandHistory.length > 50) this.commandHistory.shift();
      this.historyIndex = this.commandHistory.length;
    }
    const generation = this.executionGeneration;
    this.commandQueue = this.commandQueue
      .then(() => this.submit(input, generation))
      .catch(() => undefined);
  }

  private async submit(input: string, generation: number): Promise<void> {
    if (this.disposed || generation !== this.executionGeneration) return;
    if (input.length > 0) {
      let result: ReplResult;
      try {
        result = await this.callbacks.execute(input);
      } catch (error) {
        if (this.disposed || generation !== this.executionGeneration) return;
        const detail = error instanceof Error ? error.message : String(error);
        this.writes.fire(`\x1b[31m${detail}\x1b[0m\r\n`);
        this.writes.fire(PROMPT);
        return;
      }
      if (this.disposed || generation !== this.executionGeneration) return;
      if (result.action?.kind === 'clear') {
        this.writes.fire('\x1b[2J\x1b[H');
      } else {
        for (const line of result.output) {
          const prefix = result.ok ? '' : '\x1b[31m';
          const suffix = result.ok ? '' : '\x1b[0m';
          this.writes.fire(`${prefix}${line.replaceAll('\n', '\r\n')}${suffix}\r\n`);
        }
      }
      void Promise.resolve(this.callbacks.onResult(result)).catch(() => undefined);
      if (result.action?.kind === 'close') {
        this.closes.fire();
        return;
      }
    }
    this.writes.fire(PROMPT);
  }

  private previousHistory(): string {
    if (this.commandHistory.length === 0) return this.buffer;
    this.historyIndex = Math.max(0, this.historyIndex - 1);
    return this.commandHistory[this.historyIndex] ?? '';
  }

  private nextHistory(): string {
    if (this.commandHistory.length === 0) return this.buffer;
    this.historyIndex = Math.min(this.commandHistory.length, this.historyIndex + 1);
    return this.commandHistory[this.historyIndex] ?? '';
  }

  private replaceBuffer(value: string): void {
    if (this.buffer.length > 0) this.writes.fire('\b \b'.repeat(this.buffer.length));
    this.buffer = value;
    this.writes.fire(value);
  }

  public dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.executionGeneration += 1;
    this.callbacks.cancel();
    this.opened = false;
    this.writes.dispose();
    this.closes.dispose();
  }
}

export class NativeScriptingIntegration implements vscode.Disposable {
  private readonly repl: GhidraExRepl;
  private readonly reads = new NativeReadCancellationPool();
  private readonly output = vscode.window.createOutputChannel('GhidraEx Scripts', { log: true });
  private readonly status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 87);
  private readonly disposables: vscode.Disposable[] = [];
  private terminal: vscode.Terminal | undefined;
  private pty: GhidraExPseudoterminal | undefined;
  private running = false;
  private disposed = false;

  public constructor(
    reads: NativeReadService,
    private readonly getCurrentRow: () => number,
    private readonly getCapabilities: () => BackendCapabilitySnapshot,
    private readonly callbacks: NativeScriptingCallbacks,
  ) {
    this.repl = new GhidraExRepl(reads);
    this.status.name = 'GhidraEx scripting';
    this.status.text = '$(terminal) GX REPL';
    this.status.tooltip = 'Open the bounded native GhidraEx Script Console';
    this.status.command = 'ghidraex.scripting.openConsole';
    this.status.show();
    this.disposables.push(
      this.output,
      this.status,
      vscode.window.onDidCloseTerminal(terminal => {
        if (terminal !== this.terminal) return;
        this.pty?.dispose();
        this.pty = undefined;
        this.terminal = undefined;
        this.publishSnapshot();
        void vscode.commands.executeCommand('setContext', 'ghidraex.scriptConsoleOpen', false);
      }),
    );
    this.publishSnapshot();
  }

  public get snapshot(): NativeScriptingSnapshot {
    return {
      consoleOpen: this.terminal !== undefined,
      running: this.running,
      history: [...this.repl.history],
    };
  }

  public openConsole(): void {
    if (this.terminal === undefined) {
      this.pty = new GhidraExPseudoterminal({
        execute: input => this.reads.run(undefined, signal => this.repl.execute(input, this.context(), signal)),
        cancel: () => this.reads.cancel(),
        onResult: result => this.handleResult(result),
      });
      this.terminal = vscode.window.createTerminal({
        name: 'GhidraEx Script Console',
        iconPath: new vscode.ThemeIcon('terminal'),
        pty: this.pty,
        isTransient: true,
      });
      this.output.info('Opened native Script Console with the bounded gx command runtime.');
      this.publishSnapshot();
      void vscode.commands.executeCommand('setContext', 'ghidraex.scriptConsoleOpen', true);
    }
    this.terminal.show(false);
  }

  public async newScript(): Promise<void> {
    const templates: readonly ScriptTemplateQuickPickItem[] = [
      {
        label: '$(terminal) GX command script',
        description: 'Runnable now · bounded synthetic commands',
        detail: 'Creates an untitled .gxscript-style editor document.',
        languageId: 'ghidraex-script',
        content: [
          '# GhidraEx bounded command script',
          '# Runs in desktop and browser extension hosts without arbitrary code evaluation.',
          'program',
          'location',
          'decompile decode_packet',
          'xrefs decode_packet',
          'capabilities',
          '',
        ].join('\n'),
      },
      {
        label: '$(symbol-method) Python / PyGhidra script',
        description: 'Template only · real backend required',
        detail: 'The prototype will not execute Python without a connected Ghidra bridge.',
        languageId: 'python',
        content: [
          '# GhidraEx PyGhidra template',
          '# Execution requires a real local JVM or authenticated remote Ghidra service.',
          'from ghidra.program.model.listing import Function',
          '',
          'def run(current_program, monitor):',
          '    """Inspect the connected Ghidra Program inside a backend-owned transaction boundary."""',
          '    print(current_program.getName())',
          '',
        ].join('\n'),
      },
      {
        label: '$(coffee) Java GhidraScript',
        description: 'Template only · real backend required',
        detail: 'The prototype will not compile or execute Java without a connected Ghidra JVM.',
        languageId: 'java',
        content: [
          'import ghidra.app.script.GhidraScript;',
          '',
          'public class GhidraExScript extends GhidraScript {',
          '    @Override',
          '    protected void run() throws Exception {',
          '        println(currentProgram.getName());',
          '    }',
          '}',
          '',
        ].join('\n'),
      },
    ];
    const selected = await vscode.window.showQuickPick(templates, {
      title: 'GhidraEx: New Script',
      placeHolder: 'Choose a native editor template and runtime contract',
      matchOnDescription: true,
      matchOnDetail: true,
    });
    if (selected === undefined) return;
    const document = await vscode.workspace.openTextDocument({ language: selected.languageId, content: selected.content });
    await vscode.window.showTextDocument(document, { preview: false });
    if (selected.languageId !== 'ghidraex-script') {
      void vscode.window.showInformationMessage('Template created. Execution remains disabled until a real Ghidra backend is connected.');
    }
  }

  public async runActiveScript(): Promise<boolean> {
    const document = vscode.window.activeTextEditor?.document;
    if (document === undefined) {
      void vscode.window.showInformationMessage('Open a GhidraEx command script or Ghidra script template first.');
      return false;
    }
    if (document.languageId !== 'ghidraex-script') {
      const capabilities = this.getCapabilities();
      const detail = `Cannot execute ${document.languageId} in the prototype: ${capabilities.backend.detail}`;
      this.output.error(detail);
      this.output.show(true);
      void vscode.window.showWarningMessage(detail);
      return false;
    }

    this.setRunning(true);
    try {
      const batch = await this.reads.run(undefined, signal =>
        this.repl.executeCommandScript(document.getText(), this.context(), signal));
      this.output.info(`GX script ${batch.ok ? 'completed' : 'failed'} with ${batch.results.length} command(s).`);
      for (const result of batch.results) {
        this.logResult(result);
        await this.handleAction(result);
      }
      this.output.show(true);
      return batch.ok;
    } finally {
      this.setRunning(false);
      this.publishSnapshot();
    }
  }

  public showOutput(): void {
    this.output.show(false);
  }

  public clearHistory(): void {
    this.repl.clearHistory();
    this.publishSnapshot();
  }

  private context() {
    return { currentRow: this.getCurrentRow(), capabilities: this.getCapabilities() };
  }

  private async handleResult(result: ReplResult): Promise<void> {
    this.logResult(result);
    this.publishSnapshot();
    try {
      await this.handleAction(result);
    } catch (error) {
      this.callbacks.onError(error);
    }
  }

  private async handleAction(result: ReplResult): Promise<void> {
    if (result.action?.kind === 'navigate') {
      await this.callbacks.onNavigate(result.action.row);
    } else if (result.action?.kind === 'start-analysis') {
      await this.callbacks.onStartAnalysis();
    } else if (result.action?.kind === 'clear') {
      this.output.clear();
    } else if (result.action?.kind === 'close') {
      this.terminal?.dispose();
    }
  }

  private logResult(result: ReplResult): void {
    const summary = result.output.join('\n');
    if (result.ok) this.output.info(`gx> ${result.input}${summary ? `\n${summary}` : ''}`);
    else this.output.error(`gx> ${result.input}${summary ? `\n${summary}` : ''}`);
  }

  private setRunning(running: boolean): void {
    this.running = running;
    this.status.text = running ? '$(loading~spin) GX Script' : '$(terminal) GX REPL';
    void vscode.commands.executeCommand('setContext', 'ghidraex.scriptRunning', running);
    this.publishSnapshot();
  }

  private publishSnapshot(): void {
    this.callbacks.onSnapshot(this.snapshot);
  }

  public dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.terminal?.dispose();
    this.pty?.dispose();
    this.reads.dispose();
    void vscode.commands.executeCommand('setContext', 'ghidraex.scriptConsoleOpen', false);
    void vscode.commands.executeCommand('setContext', 'ghidraex.scriptRunning', false);
    for (const disposable of [...this.disposables].reverse()) disposable.dispose();
  }
}
