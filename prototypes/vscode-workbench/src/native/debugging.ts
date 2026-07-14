import * as vscode from 'vscode';
import type { BackendCapabilitySnapshot } from '../core/capabilities';
import {
  PROGRAM_ID,
  TOTAL_ROWS,
  formatAddress,
  type DecompileResult,
  type ProgramSummary,
  type ReferenceMatch,
} from '../core/engine';
import {
  NativeReadCancelledError,
  type NativeReadCompleteness,
  type NativeReadService,
} from '../core/readService';
import {
  listingEditorLineForGlobalRow,
  listingGlobalRowForEditorLine,
  makeListingUri,
  parseNativeDocumentUri,
} from './documents';

export const GHIDRAEX_DEBUG_TYPE = 'ghidraex';
const DEBUG_THREAD_ID = 1;
const REGISTER_VARIABLES_REFERENCE = 1;
const LOCAL_VARIABLES_REFERENCE = 2;
const PROGRAM_VARIABLES_REFERENCE = 3;

export type NativeDebugState = 'idle' | 'starting' | 'running' | 'stopped' | 'terminated';
export type NativeDebugReadState = 'stale' | 'loading' | 'current' | 'partial' | 'error';

export interface NativeDebugRegister {
  readonly name: string;
  readonly value: string;
}

export interface NativeDebugSnapshot {
  readonly sessionId?: string;
  readonly sessionName?: string;
  readonly state: NativeDebugState;
  readonly backend: 'synthetic';
  readonly row: number;
  readonly reason: string;
  readonly threadName: string;
  readonly breakpoints: readonly number[];
  readonly registers: readonly NativeDebugRegister[];
  readonly readState: NativeDebugReadState;
  readonly readDetail: string;
}

interface ProtocolRequest extends vscode.DebugProtocolMessage {
  readonly seq: number;
  readonly type: 'request';
  readonly command: string;
  readonly arguments?: Record<string, unknown>;
}

interface ProtocolBreakpoint {
  readonly line?: number;
}

export interface NativeDebugAdapterOptions {
  readonly reads: NativeReadService;
  readonly initialRow: number;
  readonly sessionId?: string;
  readonly sessionName?: string;
  readonly onSnapshot?: (snapshot: NativeDebugSnapshot) => void;
}

interface DebugReadProjection {
  readonly row: number;
  readonly program: Readonly<ProgramSummary>;
  readonly current: Readonly<DecompileResult>;
  readonly caller: Readonly<DecompileResult>;
  readonly references: readonly Readonly<ReferenceMatch>[];
  readonly completeness: NativeReadCompleteness;
  readonly warnings: readonly string[];
}

function clampRow(row: number): number {
  return Math.max(0, Math.min(TOTAL_ROWS - 1, Math.trunc(row)));
}

function registersFor(row: number): readonly NativeDebugRegister[] {
  const address = formatAddress(row);
  return [
    { name: 'RIP', value: address },
    { name: 'RSP', value: `0x${(0x7fff_f000 - row * 8).toString(16)}` },
    { name: 'RBP', value: `0x${(0x7fff_f080 - row * 8).toString(16)}` },
    { name: 'RAX', value: `0x${((row * 37) & 0xffff_ffff).toString(16).padStart(8, '0')}` },
    { name: 'RFLAGS', value: row % 2 === 0 ? '0x202' : '0x246' },
  ];
}

function isProtocolRequest(message: vscode.DebugProtocolMessage): message is ProtocolRequest {
  if (typeof message !== 'object' || message === null) return false;
  const candidate = message as Partial<ProtocolRequest>;
  return candidate.type === 'request' && typeof candidate.seq === 'number' && typeof candidate.command === 'string';
}

export class SyntheticDebugAdapter implements vscode.DebugAdapter {
  private readonly messages = new vscode.EventEmitter<vscode.DebugProtocolMessage>();
  private readonly reads: NativeReadService;
  private readonly initialRow: number;
  private readonly sessionId?: string;
  private readonly sessionName?: string;
  private readonly onSnapshot?: (snapshot: NativeDebugSnapshot) => void;
  private sequence = 1;
  private runTimer: ReturnType<typeof setTimeout> | undefined;
  private prefetchController: AbortController | undefined;
  private prefetchTask: Promise<void> = Promise.resolve();
  private prefetchSequence = 0;
  private projection: DebugReadProjection | undefined;
  private disposed = false;
  private snapshot: NativeDebugSnapshot;

  public readonly onDidSendMessage = this.messages.event;

  public constructor(options: NativeDebugAdapterOptions) {
    this.reads = options.reads;
    this.initialRow = clampRow(options.initialRow);
    this.sessionId = options.sessionId;
    this.sessionName = options.sessionName;
    this.onSnapshot = options.onSnapshot;
    this.snapshot = {
      ...(this.sessionId === undefined ? {} : { sessionId: this.sessionId }),
      ...(this.sessionName === undefined ? {} : { sessionName: this.sessionName }),
      state: 'starting',
      backend: 'synthetic',
      row: this.initialRow,
      reason: 'Awaiting launch',
      threadName: 'Synthetic trace thread',
      breakpoints: [],
      registers: registersFor(this.initialRow),
      readState: 'stale',
      readDetail: 'Debug data has not been prefetched',
    };
    this.publishSnapshot();
    this.beginPrefetch(this.initialRow);
  }

  public handleMessage(message: vscode.DebugProtocolMessage): void {
    if (!isProtocolRequest(message) || this.disposed) return;
    const args = message.arguments ?? {};

    switch (message.command) {
      case 'initialize':
        this.respond(message, {
          supportsConfigurationDoneRequest: true,
          supportsEvaluateForHovers: true,
          supportsTerminateRequest: true,
          supportsRestartRequest: true,
          supportsBreakpointLocationsRequest: true,
        });
        break;
      case 'launch':
      case 'attach': {
        const requestedRow = args['row'];
        if (typeof requestedRow === 'number' && Number.isFinite(requestedRow)) {
          this.update({ row: clampRow(requestedRow), registers: registersFor(clampRow(requestedRow)) });
        }
        this.update({ state: 'starting', reason: message.command === 'attach' ? 'Attaching synthetic trace' : 'Launching synthetic trace' });
        this.respond(message);
        this.event('output', {
          category: 'console',
          output: 'GhidraEx synthetic debug adapter: deterministic UI trace only; no process or Ghidra Debugger target is attached.\n',
        });
        this.event('initialized');
        break;
      }
      case 'configurationDone':
        this.respond(message);
        this.stopAt(this.snapshot.row, 'entry');
        break;
      case 'setBreakpoints': {
        const structured = Array.isArray(args['breakpoints']) ? args['breakpoints'] as ProtocolBreakpoint[] : [];
        const legacyLines = structured.length === 0 && Array.isArray(args['lines'])
          ? (args['lines'] as unknown[]).filter((line): line is number => typeof line === 'number')
          : [];
        const requestedLines = structured.length > 0
          ? structured.map(breakpoint => breakpoint.line)
          : legacyLines;
        const sourcePath = typeof args['source'] === 'object' && args['source'] !== null
          ? (args['source'] as { readonly path?: unknown }).path
          : undefined;
        let sourceUri: vscode.Uri | undefined;
        if (typeof sourcePath === 'string') {
          try {
            sourceUri = vscode.Uri.parse(sourcePath);
          } catch {
            sourceUri = undefined;
          }
        }
        const sourceDescriptor = sourceUri === undefined ? undefined : parseNativeDocumentUri(sourceUri);
        const resolved = requestedLines.flatMap(line => {
          if (typeof line !== 'number' || !Number.isInteger(line) || line < 1) return [];
          const editorLine = line - 1;
          const row = sourceUri !== undefined && sourceDescriptor?.kind === 'listing'
            ? listingGlobalRowForEditorLine(sourceUri, editorLine)
            : editorLine;
          return row === undefined || row < 0 || row >= TOTAL_ROWS ? [] : [{ row, line }];
        });
        const breakpoints = resolved.map(item => item.row);
        this.update({ breakpoints });
        this.respond(message, {
          breakpoints: resolved.map((item, index) => ({
            id: index + 1,
            verified: true,
            line: item.line,
            message: 'Synthetic trace breakpoint',
          })),
        });
        break;
      }
      case 'setExceptionBreakpoints':
        this.respond(message, { breakpoints: [] });
        break;
      case 'breakpointLocations': {
        const line = typeof args['line'] === 'number' ? args['line'] : this.snapshot.row + 1;
        this.respond(message, { breakpoints: [{ line }] });
        break;
      }
      case 'threads':
        this.respond(message, { threads: [{ id: DEBUG_THREAD_ID, name: this.snapshot.threadName }] });
        break;
      case 'stackTrace':
        this.withProjection(message, projection => {
          this.respond(message, { stackFrames: this.stackFrames(projection), totalFrames: 2 });
        });
        break;
      case 'scopes':
        this.respond(message, {
          scopes: [
            { name: 'Registers', variablesReference: REGISTER_VARIABLES_REFERENCE, expensive: false },
            { name: 'Locals', variablesReference: LOCAL_VARIABLES_REFERENCE, expensive: false },
            { name: 'Program', variablesReference: PROGRAM_VARIABLES_REFERENCE, expensive: false },
          ],
        });
        break;
      case 'variables':
        this.respondVariables(message, Number(args['variablesReference']));
        break;
      case 'evaluate':
        this.evaluate(message, typeof args['expression'] === 'string' ? args['expression'] : '');
        break;
      case 'continue':
        this.respond(message, { allThreadsContinued: true });
        this.continueExecution();
        break;
      case 'next':
        this.respond(message);
        this.stopAt(this.snapshot.row + 1, 'step');
        break;
      case 'stepIn':
        this.respond(message);
        this.stopAt(this.snapshot.row + 4, 'step');
        break;
      case 'stepOut':
        this.respond(message);
        this.stopAt(this.snapshot.row + 8, 'step');
        break;
      case 'pause':
        this.respond(message);
        this.stopAt(this.snapshot.row, 'pause');
        break;
      case 'restart':
        this.respond(message);
        this.stopAt(this.initialRow, 'restart');
        break;
      case 'disconnect':
      case 'terminate':
        this.respond(message);
        this.terminate(message.command);
        break;
      default:
        this.respond(message, undefined, false, `Unsupported request “${message.command}” in the synthetic adapter.`);
    }
  }

  private stackFrames(projection: DebugReadProjection): readonly Record<string, unknown>[] {
    const current = projection.current;
    const callerRow = projection.caller.row;
    const caller = projection.caller;
    const frame = (id: number, name: string, row: number): Record<string, unknown> => {
      const listingUri = makeListingUri(row);
      const editorLine = listingEditorLineForGlobalRow(listingUri, row);
      if (editorLine === undefined) throw new RangeError(`Debug row ${row} is outside its listing projection`);
      return {
        id,
        name,
        line: editorLine + 1,
        column: 1,
        source: {
          name: listingUri.path.slice(1),
          path: listingUri.toString(),
          presentationHint: 'normal',
        },
        instructionPointerReference: formatAddress(row),
      };
    };
    return [
      frame(1, current.functionName, this.snapshot.row),
      frame(2, caller.functionName, callerRow),
    ];
  }

  private variables(reference: number, projection?: DebugReadProjection): readonly Record<string, unknown>[] {
    if (reference === REGISTER_VARIABLES_REFERENCE) {
      return this.snapshot.registers.map(register => ({
        name: register.name,
        value: register.value,
        variablesReference: 0,
        memoryReference: register.name === 'RIP' ? register.value : undefined,
      }));
    }
    if (reference === LOCAL_VARIABLES_REFERENCE) {
      return [
        { name: 'payloadLength', value: String((this.snapshot.row * 13) & 0xfff), type: 'uint32_t', variablesReference: 0 },
        { name: 'decoded', value: String((this.snapshot.row * 7) & 0xff), type: 'int', variablesReference: 0 },
        { name: 'packet', value: `Packet * @ ${formatAddress(clampRow(this.snapshot.row + 32))}`, variablesReference: 0 },
      ];
    }
    if (reference === PROGRAM_VARIABLES_REFERENCE) {
      if (projection === undefined) return [];
      const summary = projection.program;
      return [
        { name: 'program', value: summary.name, variablesReference: 0 },
        { name: 'backend', value: 'synthetic (no live target)', variablesReference: 0 },
        { name: 'analysis', value: summary.status, variablesReference: 0 },
      ];
    }
    return [];
  }

  private evaluate(request: ProtocolRequest, expression: string): void {
    const normalized = expression.trim().toLowerCase();
    let result: string | undefined;
    let type: string | undefined;
    if (normalized === 'pc' || normalized === '$pc' || normalized === 'rip' || normalized === '$rip') {
      result = formatAddress(this.snapshot.row);
      type = 'address';
    } else if (normalized === 'function') {
      const projection = this.requireProjection(request);
      if (projection === undefined) return;
      result = projection.current.functionName;
      type = 'symbol';
    } else if (normalized === 'program') {
      const projection = this.requireProjection(request);
      if (projection === undefined) return;
      result = projection.program.id;
      type = 'program';
    } else if (normalized === 'backend') {
      result = 'synthetic — no process or Ghidra Debugger target';
      type = 'capability';
    } else if (normalized === 'decompile()') {
      const projection = this.requireProjection(request);
      if (projection === undefined) return;
      result = projection.current.text;
      type = 'decompiler text';
    } else if (normalized === 'xrefs()') {
      const projection = this.requireProjection(request);
      if (projection === undefined) return;
      result = projection.references
        .map(reference => `${reference.kind} ${reference.sourceAddress} ${reference.functionName}`)
        .join('\n');
      type = 'references';
    }
    if (result === undefined) {
      this.respond(request, undefined, false, `Unknown synthetic debug expression “${expression.slice(0, 512)}”. Try pc, function, backend, decompile(), or xrefs().`);
      return;
    }
    this.respond(request, { result, type, variablesReference: 0 });
  }

  private respondVariables(request: ProtocolRequest, reference: number): void {
    if (reference !== PROGRAM_VARIABLES_REFERENCE) {
      this.respond(request, { variables: this.variables(reference) });
      return;
    }
    this.withProjection(request, projection => {
      this.respond(request, { variables: this.variables(reference, projection) });
    });
  }

  private requireProjection(request: ProtocolRequest): DebugReadProjection | undefined {
    const projection = this.projection;
    if (projection !== undefined && projection.row === this.snapshot.row) return projection;
    this.respond(
      request,
      undefined,
      false,
      `Debug data is ${this.snapshot.readState}: ${this.snapshot.readDetail}`,
    );
    return undefined;
  }

  private withProjection(request: ProtocolRequest, action: (projection: DebugReadProjection) => void): void {
    const projection = this.requireProjection(request);
    if (projection !== undefined) action(projection);
  }

  private beginPrefetch(row: number, stopReason?: string): void {
    this.prefetchController?.abort();
    const controller = new AbortController();
    this.prefetchController = controller;
    const requestId = ++this.prefetchSequence;
    this.projection = undefined;
    this.update({
      readState: 'loading',
      readDetail: `Loading bounded debug projection for ${formatAddress(row)}`,
      ...(stopReason === undefined ? {} : { reason: `${stopReason} · loading debug data` }),
    });
    this.prefetchTask = this.prefetch(row, controller.signal)
      .then(projection => {
        if (!this.acceptsPrefetch(requestId, row)) return;
        this.projection = projection;
        const partial = projection.completeness !== 'COMPLETE';
        const detail = (partial
          ? `${projection.completeness}: ${projection.warnings.join(' · ') || 'backend returned incomplete debug data'}`
          : `Current for ${formatAddress(row)}`).slice(0, 1_024);
        this.update({
          readState: partial ? 'partial' : 'current',
          readDetail: detail,
          ...(stopReason === undefined ? {} : { reason: partial ? `${stopReason} · ${detail}` : stopReason }),
        });
        if (stopReason !== undefined) this.publishStoppedLocation(row, stopReason);
      })
      .catch(error => {
        if (!this.acceptsPrefetch(requestId, row) || error instanceof NativeReadCancelledError) return;
        const detail = error instanceof Error ? error.message : String(error);
        this.update({
          readState: 'error',
          readDetail: detail.slice(0, 1_024),
          ...(stopReason === undefined ? {} : { reason: `${stopReason} · debug data unavailable` }),
        });
        if (stopReason !== undefined) this.publishStoppedLocation(row, stopReason);
      });
  }

  private async prefetch(row: number, signal: AbortSignal): Promise<DebugReadProjection> {
    const context = this.reads.context;
    const callerRow = clampRow(Math.max(0, row - 64));
    const [program, current, caller, references] = await Promise.all([
      this.reads.program({ signal, scope: `dap-program-${row}` }),
      this.reads.decompile(context, row, { signal, scope: `dap-current-${row}` }),
      this.reads.decompile(context, callerRow, { signal, scope: `dap-caller-${row}` }),
      this.reads.references(context, row, 256, { signal, scope: `dap-references-${row}` }),
    ]);
    const reads = [program, current, caller, references] as const;
    const completeness: NativeReadCompleteness = reads.some(read => read.completeness === 'TRUNCATED')
      ? 'TRUNCATED'
      : reads.some(read => read.completeness === 'PARTIAL')
        ? 'PARTIAL'
        : 'COMPLETE';
    const warnings = Object.freeze(reads.flatMap(read => read.warnings).slice(0, 64));
    return Object.freeze({
      row,
      program: Object.freeze({ ...program.value }),
      current: Object.freeze({ ...current.value }),
      caller: Object.freeze({ ...caller.value }),
      references: Object.freeze(references.value.map(reference => Object.freeze({ ...reference }))),
      completeness,
      warnings,
    });
  }

  private acceptsPrefetch(requestId: number, row: number): boolean {
    return !this.disposed && requestId === this.prefetchSequence && this.snapshot.row === row &&
      this.prefetchController?.signal.aborted === false;
  }

  /** Test/host readiness hook; DAP stopped events are likewise delayed until this settles. */
  public async whenReadsSettled(): Promise<void> {
    await this.prefetchTask;
  }

  private continueExecution(): void {
    this.cancelRunTimer();
    this.update({ state: 'running', reason: 'continue' });
    this.event('continued', { threadId: DEBUG_THREAD_ID, allThreadsContinued: true });
    const nextBreakpoint = this.snapshot.breakpoints.find(row => row > this.snapshot.row);
    const destination = nextBreakpoint ?? clampRow(this.snapshot.row + 16);
    this.runTimer = setTimeout(() => {
      this.runTimer = undefined;
      if (!this.disposed && this.snapshot.state === 'running') {
        this.stopAt(destination, nextBreakpoint === undefined ? 'step' : 'breakpoint');
      }
    }, 90);
  }

  private stopAt(row: number, reason: string): void {
    this.cancelRunTimer();
    const selected = clampRow(row);
    this.update({ state: 'stopped', row: selected, reason, registers: registersFor(selected) });
    this.beginPrefetch(selected, reason);
  }

  private publishStoppedLocation(row: number, reason: string): void {
    this.event('stopped', { reason, threadId: DEBUG_THREAD_ID, allThreadsStopped: true });
    this.event('ghidraex.location', { row, address: formatAddress(row), reason });
  }

  private terminate(reason: string): void {
    this.cancelRunTimer();
    this.prefetchController?.abort();
    this.prefetchSequence += 1;
    this.update({ state: 'terminated', reason });
    this.event('terminated');
  }

  private update(update: Partial<NativeDebugSnapshot>): void {
    this.snapshot = { ...this.snapshot, ...update };
    this.publishSnapshot();
  }

  private publishSnapshot(): void {
    this.onSnapshot?.({
      ...this.snapshot,
      breakpoints: [...this.snapshot.breakpoints],
      registers: [...this.snapshot.registers],
    });
  }

  private respond(
    request: ProtocolRequest,
    body?: unknown,
    success = true,
    message?: string,
  ): void {
    this.send({
      seq: this.sequence++,
      type: 'response',
      request_seq: request.seq,
      command: request.command,
      success,
      ...(body === undefined ? {} : { body }),
      ...(message === undefined ? {} : { message: message.slice(0, 2_048) }),
    });
  }

  private event(event: string, body?: unknown): void {
    this.send({
      seq: this.sequence++,
      type: 'event',
      event,
      ...(body === undefined ? {} : { body }),
    });
  }

  private send(message: Record<string, unknown>): void {
    this.messages.fire(message as vscode.DebugProtocolMessage);
  }

  private cancelRunTimer(): void {
    if (this.runTimer === undefined) return;
    clearTimeout(this.runTimer);
    this.runTimer = undefined;
  }

  public dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.cancelRunTimer();
    this.prefetchController?.abort();
    this.prefetchSequence += 1;
    this.messages.dispose();
  }
}

export interface NativeDebugCallbacks {
  readonly onSnapshot: (snapshot: NativeDebugSnapshot) => void;
  readonly onNavigate: (row: number) => void | PromiseLike<void>;
  readonly onError: (error: unknown) => void;
}

export class NativeDebugIntegration implements vscode.Disposable {
  private readonly status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 88);
  private readonly disposables: vscode.Disposable[] = [];
  private activeSession: vscode.DebugSession | undefined;
  private currentSnapshot: NativeDebugSnapshot;

  public constructor(
    private readonly output: vscode.LogOutputChannel,
    private readonly reads: NativeReadService,
    private readonly getCapabilities: () => BackendCapabilitySnapshot,
    private readonly callbacks: NativeDebugCallbacks,
    initialRow: number,
  ) {
    this.currentSnapshot = {
      state: 'idle',
      backend: 'synthetic',
      row: clampRow(initialRow),
      reason: 'No debug session',
      threadName: 'Synthetic trace thread',
      breakpoints: [],
      registers: registersFor(clampRow(initialRow)),
      readState: 'stale',
      readDetail: 'No debug session',
    };
    this.status.name = 'GhidraEx debug session';
    this.status.command = 'ghidraex.debug.start';
    this.updateStatus();
    this.status.show();

    const provider: vscode.DebugConfigurationProvider = {
      provideDebugConfigurations: () => [this.configuration(this.currentSnapshot.row)],
      resolveDebugConfiguration: (_folder, configuration) => ({
        ...this.configuration(this.currentSnapshot.row),
        ...configuration,
        type: GHIDRAEX_DEBUG_TYPE,
        request: configuration.request || 'launch',
        name: configuration.name || 'Debug GhidraEx synthetic trace',
        backend: 'synthetic',
      }),
    };
    const descriptorFactory: vscode.DebugAdapterDescriptorFactory = {
      createDebugAdapterDescriptor: session => new vscode.DebugAdapterInlineImplementation(new SyntheticDebugAdapter({
        reads: this.reads,
        initialRow: typeof session.configuration['row'] === 'number' ? session.configuration['row'] : this.currentSnapshot.row,
        sessionId: session.id,
        sessionName: session.name,
        onSnapshot: snapshot => this.acceptSnapshot(snapshot),
      })),
    };

    this.disposables.push(
      this.status,
      vscode.debug.registerDebugConfigurationProvider(GHIDRAEX_DEBUG_TYPE, provider),
      vscode.debug.registerDebugConfigurationProvider(
        GHIDRAEX_DEBUG_TYPE,
        provider,
        vscode.DebugConfigurationProviderTriggerKind.Dynamic,
      ),
      vscode.debug.registerDebugAdapterDescriptorFactory(GHIDRAEX_DEBUG_TYPE, descriptorFactory),
      vscode.debug.onDidStartDebugSession(session => {
        if (session.type !== GHIDRAEX_DEBUG_TYPE) return;
        this.activeSession = session;
        this.output.info(`Started native VS Code debug session ${session.name} with the synthetic adapter.`);
        this.setContext(true);
      }),
      vscode.debug.onDidChangeActiveDebugSession(session => {
        if (session?.type === GHIDRAEX_DEBUG_TYPE) this.activeSession = session;
      }),
      vscode.debug.onDidTerminateDebugSession(session => {
        if (session.type !== GHIDRAEX_DEBUG_TYPE) return;
        if (this.activeSession?.id === session.id) this.activeSession = undefined;
        this.acceptSnapshot({ ...this.currentSnapshot, state: 'terminated', reason: 'session ended' });
        this.output.info(`Ended native VS Code debug session ${session.name}.`);
        this.setContext(false);
      }),
      vscode.debug.onDidReceiveDebugSessionCustomEvent(event => {
        if (event.session.type !== GHIDRAEX_DEBUG_TYPE || event.event !== 'ghidraex.location') return;
        const row = (event.body as { readonly row?: unknown } | undefined)?.row;
        if (typeof row === 'number' && Number.isInteger(row)) {
          void Promise.resolve(this.callbacks.onNavigate(clampRow(row))).catch(error => this.callbacks.onError(error));
        }
      }),
    );
    this.callbacks.onSnapshot(this.currentSnapshot);
    this.setContext(false);
  }

  public get snapshot(): NativeDebugSnapshot {
    return this.currentSnapshot;
  }

  public async start(row: number): Promise<boolean> {
    if (this.activeSession !== undefined) {
      await vscode.commands.executeCommand('workbench.view.debug');
      return true;
    }
    const capabilities = this.getCapabilities();
    if (capabilities.selectedMode !== 'synthetic') {
      void vscode.window.showWarningMessage(
        `The ${capabilities.selectedMode} Ghidra backend is not ready. Starting the explicitly synthetic debug trace.`,
      );
    }
    this.acceptSnapshot({
      ...this.currentSnapshot,
      state: 'starting',
      row: clampRow(row),
      reason: 'Starting VS Code debug session',
      registers: registersFor(clampRow(row)),
    });
    const started = await vscode.debug.startDebugging(undefined, this.configuration(row));
    if (!started) {
      this.acceptSnapshot({ ...this.currentSnapshot, state: 'idle', reason: 'Debug session did not start' });
    }
    return started;
  }

  public async stop(): Promise<void> {
    if (this.activeSession !== undefined) await vscode.debug.stopDebugging(this.activeSession);
  }

  public async continue(): Promise<void> {
    await this.request('continue');
  }

  public async pause(): Promise<void> {
    await this.request('pause');
  }

  public async showConsole(): Promise<void> {
    await vscode.commands.executeCommand('workbench.debug.action.focusRepl');
  }

  private async request(command: 'continue' | 'pause'): Promise<void> {
    if (this.activeSession === undefined) {
      void vscode.window.showInformationMessage('No GhidraEx debug session is active.');
      return;
    }
    await this.activeSession.customRequest(command, { threadId: DEBUG_THREAD_ID });
  }

  private configuration(row: number): vscode.DebugConfiguration {
    return {
      type: GHIDRAEX_DEBUG_TYPE,
      name: 'Debug GhidraEx synthetic trace',
      request: 'launch',
      backend: 'synthetic',
      programId: PROGRAM_ID,
      row: clampRow(row),
      stopOnEntry: true,
    };
  }

  private acceptSnapshot(snapshot: NativeDebugSnapshot): void {
    this.currentSnapshot = snapshot;
    this.callbacks.onSnapshot(snapshot);
    this.updateStatus();
    this.setContext(snapshot.state !== 'idle' && snapshot.state !== 'terminated', snapshot.state === 'stopped');
  }

  private updateStatus(): void {
    const state = this.currentSnapshot.state;
    this.status.text = state === 'idle' || state === 'terminated'
      ? '$(debug-alt) GX Debug'
      : state === 'stopped'
        ? `$(debug-pause) ${formatAddress(this.currentSnapshot.row)}`
        : `$(debug-continue) GX ${state}`;
    this.status.tooltip = state === 'idle' || state === 'terminated'
      ? 'Start the synthetic native Debug Adapter Protocol session'
      : `${this.currentSnapshot.sessionName ?? 'GhidraEx debug'} · ${state} · ${this.currentSnapshot.reason}\nSynthetic trace only; no real target.`;
    this.status.command = state === 'idle' || state === 'terminated'
      ? 'ghidraex.debug.start'
      : 'ghidraex.debug.showConsole';
  }

  private setContext(active: boolean, stopped = this.currentSnapshot.state === 'stopped'): void {
    void vscode.commands.executeCommand('setContext', 'ghidraex.debugActive', active);
    void vscode.commands.executeCommand('setContext', 'ghidraex.debugStopped', active && stopped);
  }

  public dispose(): void {
    this.setContext(false, false);
    for (const disposable of [...this.disposables].reverse()) disposable.dispose();
  }
}
