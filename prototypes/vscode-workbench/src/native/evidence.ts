import * as vscode from 'vscode';
import type { EvidenceRecord } from '../core/engine';
import { NativeReadCancelledError, type NativeReadService } from '../core/readService';
import { LatestNativeRead } from './asyncReads';
import { parseNativeDocumentUri, type ListingLineResolver } from './documents';

export class NativeEvidenceIntegration implements vscode.Disposable, vscode.CodeActionProvider {
  private readonly diagnostics = vscode.languages.createDiagnosticCollection('ghidraex');
  private readonly comments = vscode.comments.createCommentController('ghidraex.evidence', 'GhidraEx Agent Evidence');
  private readonly diagnosticRead = new LatestNativeRead();
  private readonly threadRead = new LatestNativeRead();
  private thread: vscode.CommentThread | undefined;

  public constructor(
    private readonly reads: NativeReadService,
    private readonly listing: ListingLineResolver,
  ) {
    this.comments.options = {
      prompt: 'Evidence threads are read-only in this prototype.',
      placeHolder: 'Agent evidence is provenance-bearing and cannot mutate the program.',
    };
  }

  public async publishDocument(document: vscode.TextDocument, row?: number): Promise<void> {
    const descriptor = parseNativeDocumentUri(document.uri);
    if (descriptor?.kind !== 'listing' || document.lineCount === 0) return;
    const selected = row ?? descriptor.startRow;
    const request = this.diagnosticRead.begin();
    try {
      const evidence = (await this.reads.evidence(descriptor.context, selected, 64, {
        signal: request.signal,
        scope: `evidence-diagnostics:${document.uri.toString()}`,
      })).value;
      if (!this.diagnosticRead.accepts(request.id)) return;
      const diagnostics: vscode.Diagnostic[] = [];
      for (const item of evidence) {
        const editorLine = this.listing.editorLineForGlobalRow(document.uri, item.row);
        if (editorLine === undefined) continue;
        const diagnostic = new vscode.Diagnostic(
          document.lineAt(editorLine).range,
          `${item.classification === 'fact' ? 'AI evidence' : 'Hypothesis'} ` +
            `(${Math.round(item.confidence * 100)}%): ${item.detail}`,
          item.classification === 'fact'
            ? vscode.DiagnosticSeverity.Information
            : vscode.DiagnosticSeverity.Warning,
        );
        diagnostic.source = 'GhidraEx Agent';
        diagnostic.code = `GX-${item.classification === 'fact' ? 'EVIDENCE' : 'HYPOTHESIS'}-${item.id}`;
        diagnostics.push(diagnostic);
      }
      this.diagnostics.set(document.uri, diagnostics);
    } catch (error) {
      if (error instanceof NativeReadCancelledError || request.signal.aborted) return;
      throw error;
    }
  }

  public async showEvidenceThread(document: vscode.TextDocument, row: number): Promise<void> {
    const editorLine = this.listing.editorLineForGlobalRow(document.uri, row);
    const descriptor = parseNativeDocumentUri(document.uri);
    if (descriptor?.kind !== 'listing' || editorLine === undefined) return;
    const request = this.threadRead.begin();
    let evidence: readonly EvidenceRecord[];
    try {
      evidence = (await this.reads.evidence(descriptor.context, row, 64, {
        signal: request.signal,
        scope: `evidence-thread:${document.uri.toString()}`,
      })).value;
    } catch (error) {
      if (error instanceof NativeReadCancelledError || request.signal.aborted) return;
      throw error;
    }
    if (!this.threadRead.accepts(request.id)) return;
    this.thread?.dispose();
    const comments: vscode.Comment[] = evidence.map(item => {
      const body = new vscode.MarkdownString(undefined, true);
      body.appendMarkdown(`**${item.classification.toUpperCase()} · ${Math.round(item.confidence * 100)}%**\n\n`);
      body.appendMarkdown(`${item.detail}\n\n`);
      body.appendMarkdown(`[Open ${item.address}](command:ghidraex.openLocation?${encodeURIComponent(JSON.stringify([item.row]))})`);
      body.isTrusted = { enabledCommands: ['ghidraex.openLocation'] };
      return {
        body,
        mode: vscode.CommentMode.Preview,
        author: { name: 'GhidraEx Agent' },
        contextValue: 'ghidraex.readonlyEvidence',
        label: `${Math.round(item.confidence * 100)}% ${item.classification}`,
      };
    });
    this.thread = this.comments.createCommentThread(
      document.uri,
      document.lineAt(editorLine).range,
      comments,
    );
    this.thread.label = 'Evidence for selected instruction';
    this.thread.canReply = false;
    this.thread.collapsibleState = vscode.CommentThreadCollapsibleState.Expanded;
  }

  public provideCodeActions(
    document: vscode.TextDocument,
    _range: vscode.Range | vscode.Selection,
    context: vscode.CodeActionContext,
  ): vscode.CodeAction[] {
    if (!context.diagnostics.some(diagnostic => diagnostic.source === 'GhidraEx Agent')) return [];
    const diagnostic = context.diagnostics.find(candidate => candidate.source === 'GhidraEx Agent');
    const row = diagnostic === undefined
      ? undefined
      : this.listing.lineRef(document.uri, diagnostic.range.start.line)?.globalRow;
    const inspect = new vscode.CodeAction('Inspect GhidraEx evidence', vscode.CodeActionKind.QuickFix);
    inspect.command = {
      command: 'ghidraex.explainSelection',
      title: 'Inspect GhidraEx evidence',
      ...(row === undefined ? {} : { arguments: [row] }),
    };
    inspect.diagnostics = context.diagnostics.filter(diagnostic => diagnostic.source === 'GhidraEx Agent');
    return [inspect];
  }

  public dispose(): void {
    this.diagnosticRead.dispose();
    this.threadRead.dispose();
    this.thread?.dispose();
    this.comments.dispose();
    this.diagnostics.dispose();
  }
}
