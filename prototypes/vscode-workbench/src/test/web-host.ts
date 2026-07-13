import * as vscode from 'vscode';
import { TOTAL_ROWS, formatAddress } from '../core/engine';
import { DECOMPILER_SCHEME, LISTING_SCHEME } from '../native/documents';

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

  assert(listing.lineCount === TOTAL_ROWS, `Expected ${TOTAL_ROWS} listing lines; got ${listing.lineCount}.`);
  assert(listing.lineAt(0).text.startsWith(formatAddress(0)), 'The first native listing address is incorrect.');
  assert(
    listing.lineAt(TOTAL_ROWS - 1).text.startsWith(formatAddress(TOTAL_ROWS - 1)),
    'The final native listing address is incorrect.',
  );
  assert(listing.languageId === 'ghidraex-listing', `Unexpected listing language: ${listing.languageId}`);
  assert(decompiler.languageId === 'c', `Unexpected decompiler language: ${decompiler.languageId}`);
  assert(decompiler.getText().includes('payloadLength'), 'The native decompiler content was not populated.');

  const visibleSchemes = new Set(vscode.window.visibleTextEditors.map(editor => editor.document.uri.scheme));
  assert(visibleSchemes.has(LISTING_SCHEME), 'The listing is not visible in a native editor group.');
  assert(visibleSchemes.has(DECOMPILER_SCHEME), 'The decompiler is not visible in a native editor group.');

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

  await vscode.commands.executeCommand('ghidraex.refreshViews');

  // Start twice to cover the duplicate-request guard, then cancel through the
  // public command while the first native progress operation is still queued.
  const analysisRun = vscode.commands.executeCommand('ghidraex.startAnalysis');
  await vscode.commands.executeCommand('ghidraex.startAnalysis');
  await new Promise(resolve => setTimeout(resolve, 50));
  await vscode.commands.executeCommand('ghidraex.cancelAnalysis');
  await analysisRun;
}
