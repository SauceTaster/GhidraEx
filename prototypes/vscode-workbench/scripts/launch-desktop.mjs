import { mkdir } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
// macOS limits Unix-domain socket paths to roughly 103 bytes. Keeping the
// isolated profile beneath the repository can make VS Code's main IPC socket
// too long, causing the desktop host to exit before a window is created.
const runtimeRoot = resolve(tmpdir(), 'ghidraex-vscode');
const profile = resolve(runtimeRoot, 'profile');
const extensions = resolve(runtimeRoot, 'extensions');
const workspace = resolve(root, 'test-fixtures');

await Promise.all([
  mkdir(profile, { recursive: true }),
  mkdir(extensions, { recursive: true }),
]);

const child = spawn('code', [
  '--new-window',
  '--user-data-dir', profile,
  '--extensions-dir', extensions,
  `--extensionDevelopmentPath=${root}`,
  workspace,
], {
  detached: true,
  stdio: 'ignore',
  // A launcher invoked from inside VS Code may inherit the current window's
  // CLI socket. Remove it so the isolated profile becomes a separate process.
  env: Object.fromEntries(
    Object.entries(process.env).filter(([name]) => name !== 'VSCODE_IPC_HOOK_CLI'),
  ),
});

child.unref();
