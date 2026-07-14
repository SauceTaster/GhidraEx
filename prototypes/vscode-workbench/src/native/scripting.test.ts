import { describe, expect, it, vi } from 'vitest';

vi.mock('vscode', () => {
  class EventEmitter<T> {
    private listeners: ((value: T) => void)[] = [];
    public readonly event = (listener: (value: T) => void) => {
      this.listeners.push(listener);
      return { dispose: (): void => { this.listeners = this.listeners.filter(item => item !== listener); } };
    };
    public fire(value: T): void {
      for (const listener of this.listeners) listener(value);
    }
    public dispose(): void {
      this.listeners = [];
    }
  }
  class ThemeIcon {
    public constructor(public readonly id: string) {}
  }
  return {
    EventEmitter,
    ThemeIcon,
    window: {},
    workspace: {},
    commands: {},
    StatusBarAlignment: { Left: 1 },
  };
});

import { GhidraExPseudoterminal } from './scripting';

async function settle(): Promise<void> {
  await new Promise(resolve => setTimeout(resolve, 0));
}

describe('native GhidraEx pseudoterminal', () => {
  it('renders an explicit bounded-runtime banner and executes entered commands', async () => {
    const executed: string[] = [];
    const writes: string[] = [];
    const terminal = new GhidraExPseudoterminal({
      execute: async input => {
        executed.push(input);
        return { input, ok: true, output: ['orbit-controller-v1'] };
      },
      cancel: () => undefined,
      onResult: () => undefined,
    });
    terminal.onDidWrite(value => writes.push(value));
    terminal.open();
    terminal.handleInput('program\r');
    await settle();
    expect(writes.join('')).toContain('Bounded gx commands');
    expect(writes.join('')).toContain('Python, Java, PyGhidra, and GhidraScript require a connected real backend');
    expect(executed).toEqual(['program']);
    expect(writes.join('')).toContain('orbit-controller-v1');
    terminal.dispose();
  });

  it('supports line editing, cancellation, and bounded history navigation', async () => {
    const executed: string[] = [];
    const terminal = new GhidraExPseudoterminal({
      execute: async input => {
        executed.push(input);
        return { input, ok: true, output: [] };
      },
      cancel: () => undefined,
      onResult: () => undefined,
    });
    terminal.onDidWrite(() => undefined);
    terminal.open();
    terminal.handleInput('programx\x7f\r');
    terminal.handleInput('location\r');
    terminal.handleInput('\x1b[A\r');
    await settle();
    await settle();
    await settle();
    terminal.handleInput('discard\x03');
    expect(executed).toEqual(['program', 'location', 'location']);
    terminal.dispose();
  });

  it('emits close when the REPL returns an exit action', async () => {
    let closed = false;
    const terminal = new GhidraExPseudoterminal({
      execute: async input => ({ input, ok: true, output: ['bye'], action: { kind: 'close' } }),
      cancel: () => undefined,
      onResult: () => undefined,
    });
    terminal.onDidWrite(() => undefined);
    terminal.onDidClose(() => { closed = true; });
    terminal.open();
    terminal.handleInput('exit\r');
    await settle();
    expect(closed).toBe(true);
    terminal.dispose();
  });
});
