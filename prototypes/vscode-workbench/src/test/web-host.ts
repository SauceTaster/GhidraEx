import * as vscode from 'vscode';
import { formatAddress } from '../core/engine';
import {
  DECOMPILER_SCHEME,
  LISTING_SCHEME,
  LISTING_WINDOW_ROWS,
  listingGlobalRowForEditorLine,
  parseNativeDocumentUri,
} from '../native/documents';

function assert(condition: unknown, detail: string): asserts condition {
  if (!condition) throw new Error(detail);
}

async function waitFor<T>(read: () => T | undefined, detail: string): Promise<T> {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    const value = read();
    if (value !== undefined) return value;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error(detail);
}

export async function run(): Promise<void> {
  const extension = vscode.extensions.getExtension('ghidraex.ghidraex-vscode-workbench');
  if (extension === undefined) throw new Error('GhidraEx extension was not discovered by the web extension host.');
  await extension.activate();
  await vscode.commands.executeCommand('ghidraex.openWorkbench');

  const commands = await vscode.commands.getCommands(true);
  for (const required of [
    'ghidraex.openWorkbench',
    'ghidraex.openListing',
    'ghidraex.openDecompiler',
    'ghidraex.jumpToAddress',
    'ghidraex.searchSymbols',
    'ghidraex.startAnalysis',
    'ghidraex.showXrefs',
    'ghidraex.explainSelection',
    'ghidraex.debug.start',
    'ghidraex.debug.stop',
    'ghidraex.debug.continue',
    'ghidraex.debug.pause',
    'ghidraex.debug.showConsole',
    'ghidraex.scripting.openConsole',
    'ghidraex.scripting.newScript',
    'ghidraex.scripting.runActiveScript',
    'ghidraex.capabilities.selectBackend',
    'ghidraex.capabilities.copyReport',
  ]) {
    if (!commands.includes(required)) throw new Error(`Missing command: ${required}`);
  }

  const listing = await waitFor(
    () => vscode.workspace.textDocuments.find(document => document.uri.scheme === LISTING_SCHEME),
    'The native listing TextDocument did not open.',
  );
  const decompiler = await waitFor(
    () => vscode.workspace.textDocuments.find(document => document.uri.scheme === DECOMPILER_SCHEME),
    'The native decompiler TextDocument did not open.',
  );

  const listingDescriptor = parseNativeDocumentUri(listing.uri);
  assert(listingDescriptor?.kind === 'listing', 'The listing URI has no bounded semantic descriptor.');
  assert(listing.lineCount === listingDescriptor.rowCount, 'The listing content and semantic window disagree.');
  assert(listing.lineCount <= LISTING_WINDOW_ROWS, `The listing exceeded ${LISTING_WINDOW_ROWS} rows.`);
  assert(listing.lineAt(0).text.startsWith(formatAddress(0)), 'The first native listing address is incorrect.');
  assert(
    listing.lineAt(listing.lineCount - 1).text.startsWith(formatAddress(listingDescriptor.startRow + listing.lineCount - 1)),
    'The bounded listing final address is incorrect.',
  );
  assert(listing.languageId === 'ghidraex-listing', `Unexpected listing language: ${listing.languageId}`);
  assert(decompiler.languageId === 'c', `Unexpected decompiler language: ${decompiler.languageId}`);
  assert(decompiler.getText().includes('payloadLength'), 'The native decompiler content was not populated.');

  await waitFor(
    () => vscode.window.visibleTextEditors.some(editor => editor.document.uri.scheme === LISTING_SCHEME) ? true : undefined,
    'The listing is not visible in a native editor group.',
  );
  await waitFor(
    () => vscode.window.visibleTextEditors.some(editor => editor.document.uri.scheme === DECOMPILER_SCHEME) ? true : undefined,
    'The decompiler is not visible in a native editor group.',
  );

  const symbols = await vscode.commands.executeCommand<readonly vscode.DocumentSymbol[]>(
    'vscode.executeDocumentSymbolProvider',
    listing.uri,
  );
  assert(symbols !== undefined && symbols.some(symbol => symbol.name === 'decode_packet'), 'Native Outline symbols are missing.');

  const symbolText = listing.lineAt(448).text;
  const symbolOffset = symbolText.indexOf('decode_packet');
  assert(symbolOffset >= 0, 'decode_packet was not rendered in the native listing.');
  const definitions = await vscode.commands.executeCommand<readonly vscode.Location[]>(
    'vscode.executeDefinitionProvider',
    listing.uri,
    new vscode.Position(448, symbolOffset + 1),
  );
  assert(definitions !== undefined && definitions.some(location => location.range.start.line === 448), 'Native definition navigation failed.');

  const references = await vscode.commands.executeCommand<readonly vscode.Location[]>(
    'vscode.executeReferenceProvider',
    listing.uri,
    new vscode.Position(448, symbolOffset + 1),
  );
  assert(references !== undefined && references.length > 0, 'Native reference navigation returned no locations.');

  const diagnostics = vscode.languages.getDiagnostics(listing.uri);
  assert(diagnostics.some(diagnostic => diagnostic.source === 'GhidraEx Agent'), 'Agent evidence was not published to Problems.');

  const evidenceDiagnostic = diagnostics.find(diagnostic => diagnostic.source === 'GhidraEx Agent');
  assert(evidenceDiagnostic !== undefined, 'No agent diagnostic was available for Code Actions.');
  const codeActions = await vscode.commands.executeCommand<readonly (vscode.CodeAction | vscode.Command)[]>(
    'vscode.executeCodeActionProvider',
    listing.uri,
    evidenceDiagnostic.range,
  );
  assert(
    codeActions !== undefined && codeActions.some(action => action.title === 'Inspect GhidraEx evidence'),
    'The native agent-evidence Code Action is missing.',
  );

  // Context-menu commands receive provider nodes, not bare row numbers. This
  // mirrors a Symbols TreeView item and verifies that navigation unwraps it.
  await vscode.commands.executeCommand('ghidraex.openLocation', {
    kind: 'symbol',
    symbol: { row: 2_816 },
  });
  await waitFor(
    () => vscode.window.visibleTextEditors.find(editor =>
      editor.document.uri.scheme === LISTING_SCHEME && editor.selection.active.line === 2_816),
    'A nested Symbols TreeView argument did not navigate the listing.',
  );
  await waitFor(
    () => vscode.window.visibleTextEditors.find(editor =>
      editor.document.uri.scheme === DECOMPILER_SCHEME && editor.document.uri.query.includes('row=2816')),
    'TreeView navigation did not synchronize the native decompiler.',
  );

  // Cross a 4K projection boundary. Editor line zero now represents global row
  // 8192, proving commands do not equate a native document line with a program row.
  await vscode.commands.executeCommand('ghidraex.openLocation', { row: 8_192 });
  const secondProjection = await waitFor(
    () => vscode.window.visibleTextEditors.find(editor =>
      editor.document.uri.scheme === LISTING_SCHEME &&
      listingGlobalRowForEditorLine(editor.document.uri, editor.selection.active.line) === 8_192),
    'Cross-window navigation did not translate the global row.',
  );
  assert(secondProjection.selection.active.line === 0, 'The second listing projection did not restart at editor line zero.');
  await vscode.commands.executeCommand('ghidraex.openLocation', { row: 2_816 });

  await vscode.commands.executeCommand('ghidraex.refreshViews');

  const debugLocations: number[] = [];
  const debugLocationSubscription = vscode.debug.onDidReceiveDebugSessionCustomEvent(event => {
    if (event.session.type !== 'ghidraex' || event.event !== 'ghidraex.location') return;
    const row = (event.body as { readonly row?: unknown } | undefined)?.row;
    if (typeof row === 'number' && Number.isInteger(row)) debugLocations.push(row);
  });
  const debugBreakpoint = new vscode.SourceBreakpoint(new vscode.Location(listing.uri, new vscode.Position(2_820, 0)));
  vscode.debug.addBreakpoints([debugBreakpoint]);
  assert(vscode.debug.breakpoints.includes(debugBreakpoint), 'VS Code did not retain the native listing SourceBreakpoint.');
  const debugStarted = await vscode.commands.executeCommand<boolean>('ghidraex.debug.start');
  assert(debugStarted === true, 'The native inline debug adapter did not start.');
  const debugSession = await waitFor(
    () => vscode.debug.activeDebugSession?.type === 'ghidraex' ? vscode.debug.activeDebugSession : undefined,
    'The GhidraEx Debug Adapter Protocol session did not become active.',
  );
  const breakpointResponse = await debugSession.customRequest('setBreakpoints', {
    source: { name: 'orbit-controller-v1 listing', path: listing.uri.toString() },
    breakpoints: [{ line: 2_821 }],
  }) as { readonly breakpoints?: readonly { readonly verified?: boolean; readonly line?: number }[] };
  assert(
    breakpointResponse.breakpoints?.[0]?.verified === true && breakpointResponse.breakpoints[0].line === 2_821,
    'The inline adapter did not verify the native listing breakpoint.',
  );
  const threads = await debugSession.customRequest('threads') as { readonly threads?: readonly { readonly name?: string }[] };
  assert(threads.threads?.[0]?.name === 'Synthetic trace thread', 'The synthetic native debug thread is missing.');
  const stack = await debugSession.customRequest('stackTrace', { threadId: 1 }) as {
    readonly stackFrames?: readonly { readonly name?: string; readonly instructionPointerReference?: string }[];
  };
  assert(stack.stackFrames?.[0]?.name !== undefined, 'The native debug stack is empty.');
  assert(
    stack.stackFrames?.[0]?.instructionPointerReference?.startsWith('0x') === true,
    'The native debug frame has no instruction address.',
  );
  await waitFor(
    () => debugLocations.includes(2_816) ? true : undefined,
    'The synthetic debug session did not publish its entry location.',
  );
  const evaluation = await debugSession.customRequest('evaluate', { expression: 'backend', context: 'repl' }) as {
    readonly result?: string;
  };
  assert(evaluation.result?.includes('no process or Ghidra Debugger target') === true, 'Debug backend truth is not visible in the Debug Console contract.');
  await vscode.commands.executeCommand('ghidraex.debug.continue');
  await waitFor(
    () => debugLocations.includes(2_820) ? true : undefined,
    'Continuing the debug session did not stop at the next listing breakpoint.',
  );
  await waitFor(
    () => vscode.window.visibleTextEditors.find(editor =>
      editor.document.uri.scheme === LISTING_SCHEME && editor.selection.active.line === 2_820),
    'Continuing the debug session did not synchronize the next listing breakpoint.',
  );
  await vscode.commands.executeCommand('ghidraex.debug.stop');
  await waitFor(
    () => vscode.debug.activeDebugSession?.type === 'ghidraex' ? undefined : true,
    'The GhidraEx debug session did not terminate.',
  );
  vscode.debug.removeBreakpoints([debugBreakpoint]);
  debugLocationSubscription.dispose();

  const scriptDocument = await vscode.workspace.openTextDocument({
    language: 'ghidraex-script',
    content: '# Browser-host command script\nprogram\ndecompile decode_packet\nplugins\n',
  });
  await vscode.window.showTextDocument(scriptDocument, { preview: false });
  const scriptPassed = await vscode.commands.executeCommand<boolean>('ghidraex.scripting.runActiveScript');
  assert(scriptPassed === true, 'The bounded native gx command script did not complete.');
  await vscode.commands.executeCommand('ghidraex.scripting.openConsole');
  const scriptTerminal = await waitFor(
    () => vscode.window.terminals.find(terminal => terminal.name === 'GhidraEx Script Console'),
    'The native GhidraEx pseudoterminal did not open.',
  );
  scriptTerminal.sendText('program');
  scriptTerminal.sendText('exit');

  // Start twice to cover the duplicate-request guard, then cancel through the
  // public command while the first native progress operation is still queued.
  const analysisRun = vscode.commands.executeCommand('ghidraex.startAnalysis');
  await vscode.commands.executeCommand('ghidraex.startAnalysis');
  await new Promise(resolve => setTimeout(resolve, 50));
  await vscode.commands.executeCommand('ghidraex.cancelAnalysis');
  await analysisRun;
}
