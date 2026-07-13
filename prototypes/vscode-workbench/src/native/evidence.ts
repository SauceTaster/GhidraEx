import * as vscode from 'vscode';
import type { SyntheticEngine } from '../core/engine';

export class NativeEvidenceIntegration implements vscode.Disposable, vscode.CodeActionProvider {
  private readonly diagnostics = vscode.languages.createDiagnosticCollection('ghidraex');
  private readonly comments = vscode.comments.createCommentController('ghidraex.evidence', 'GhidraEx Agent Evidence');
  private thread: vscode.CommentThread | undefined;

  public constructor(private readonly engine: SyntheticEngine) {
    this.comments.options = {
      prompt: 'Evidence threads are read-only in this prototype.',
      placeHolder: 'Agent evidence is provenance-bearing and cannot mutate the program.',
    };
  }

  public publishDocument(document: vscode.TextDocument): void {
    if (document.uri.scheme !== 'ghidraex-listing' || document.lineCount === 0) return;
    const diagnostics: vscode.Diagnostic[] = [];

    const lengthGuard = new vscode.Diagnostic(
      document.lineAt(448).range,
      'AI evidence (92%): payloadLength is bounded before decode_packet.',
      vscode.DiagnosticSeverity.Information,
    );
    lengthGuard.source = 'GhidraEx Agent';
    lengthGuard.code = 'GX-EVIDENCE-001';
    diagnostics.push(lengthGuard);

    const signedness = new vscode.Diagnostic(
      document.lineAt(452).range,
      'Hypothesis (68%): review the byte-to-integer widening for a signedness mismatch.',
      vscode.DiagnosticSeverity.Warning,
    );
    signedness.source = 'GhidraEx Agent';
    signedness.code = 'GX-HYPOTHESIS-002';
    diagnostics.push(signedness);

    this.diagnostics.set(document.uri, diagnostics);
  }

  public showEvidenceThread(document: vscode.TextDocument, row: number): void {
    if (document.uri.scheme !== 'ghidraex-listing' || row < 0 || row >= document.lineCount) return;
    this.thread?.dispose();
    const evidence = this.engine.evidenceFor(row);
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
      document.lineAt(row).range,
      comments,
    );
    this.thread.label = 'Evidence for selected instruction';
    this.thread.canReply = false;
    this.thread.collapsibleState = vscode.CommentThreadCollapsibleState.Expanded;
  }

  public provideCodeActions(
    _document: vscode.TextDocument,
    _range: vscode.Range | vscode.Selection,
    context: vscode.CodeActionContext,
  ): vscode.CodeAction[] {
    if (!context.diagnostics.some(diagnostic => diagnostic.source === 'GhidraEx Agent')) return [];
    const inspect = new vscode.CodeAction('Inspect GhidraEx evidence', vscode.CodeActionKind.QuickFix);
    inspect.command = { command: 'ghidraex.explainSelection', title: 'Inspect GhidraEx evidence' };
    inspect.diagnostics = context.diagnostics.filter(diagnostic => diagnostic.source === 'GhidraEx Agent');
    return [inspect];
  }

  public dispose(): void {
    this.thread?.dispose();
    this.comments.dispose();
    this.diagnostics.dispose();
  }
}
