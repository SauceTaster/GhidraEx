import type {
  BackendState,
  Capability,
  DebuggerState,
  ExtensionState,
  ScriptResult,
} from "./types";

const DEFAULT_BACKEND: BackendState = {
  mode: "synthetic-fixture",
  health: "not-configured",
  version: "deterministic",
  launcher: "Set GHIDRA_HOME to enable the Ghidra sidecar",
  capabilities: ["program.read", "debug.trace.read"],
};

function capturedTrace(snapshot = 142, latestSnapshot = 142): DebuggerState {
  const stale = snapshot < latestSnapshot;
  return {
    mode: "captured-trace",
    status: stale ? "past-snapshot" : "paused",
    trace: "quartz-crash-2026-07-12",
    thread: "Thread 4 · worker/io",
    frame: 0,
    snapshot,
    latestSnapshot,
    staleMemory: stale,
    writesEnabled: false,
    registers: {
      RIP: "0x00000000004011d0",
      RSP: "0x00007fffffffdb80",
      RBP: "0x00007fffffffdc10",
      RAX: stale ? "0x000000000000002a  stale" : "0x0000000000000030",
      EFLAGS: "0x00000246  ZF PF",
    },
    stack: [
      "#0  FUN_004011d0 + 0x40",
      "#1  dispatch_payload + 0xb8",
      "#2  worker_loop + 0x114",
      "#3  pthread_start + 0x88",
    ],
    watches: [
      "packet->payload_size = 0x2a",
      "session->state = ACTIVE",
      "message_type = MSG_DATA",
    ],
  };
}

export class AdvancedWorkbenchController {
  backend: BackendState;
  debugger: DebuggerState = capturedTrace();
  extensions: ExtensionState[];

  constructor(backend: BackendState = DEFAULT_BACKEND) {
    this.backend = { ...backend, capabilities: [...backend.capabilities] };
    this.extensions = this.createExtensions();
  }

  updateBackend(backend: BackendState): void {
    this.backend = { ...backend, capabilities: [...backend.capabilities] };
    this.extensions = this.createExtensions();
  }

  selectSnapshot(snapshot: number): DebuggerState {
    if (!Number.isInteger(snapshot) || snapshot < 0 || snapshot > this.debugger.latestSnapshot) {
      throw new Error("Snapshot is outside the captured trace");
    }
    this.debugger = capturedTrace(snapshot, this.debugger.latestSnapshot);
    return this.debugger;
  }

  forkEmulator(): DebuggerState {
    this.debugger = {
      ...this.debugger,
      mode: "emulator",
      status: "paused",
      trace: `${this.debugger.trace} / emulator-fork`,
      staleMemory: false,
      writesEnabled: true,
      registers: { ...this.debugger.registers },
      stack: [...this.debugger.stack],
      watches: [...this.debugger.watches, "branch_taken = false  [emulated]"],
    };
    return this.debugger;
  }

  stepEmulator(delta: number): DebuggerState {
    if (this.debugger.mode !== "emulator") throw new Error("Stepping requires an emulator fork");
    const snapshot = Math.max(0, this.debugger.snapshot + delta);
    const latestSnapshot = Math.max(snapshot, this.debugger.latestSnapshot);
    const rip = Math.max(0, 0x004011d0 + delta * 4);
    this.debugger = {
      ...this.debugger,
      snapshot,
      latestSnapshot,
      registers: { ...this.debugger.registers, RIP: `0x${rip.toString(16).padStart(16, "0")}` },
    };
    return this.debugger;
  }

  evaluate(source: string): ScriptResult {
    const expression = source.trim();
    const lower = expression.toLocaleLowerCase();
    if (!expression) return result("blocked", "Enter a script or REPL expression.");
    if (containsAny(lower, "processbuilder", "runtime.getruntime", "system.exit")) {
      return result("blocked", "Process execution is blocked in the preview worker.", ["process.exec"]);
    }
    if (containsAny(lower, "socket(", "http://", "https://", "urlconnection")) {
      return result("blocked", "Network access is not granted to this script.", ["network"]);
    }
    if (containsAny(lower, "setname(", "rename ", "setcomment(", "createbookmark(", "writememory(")) {
      return result(
        "approval-required",
        "Dry-run only: review the exact mutation plan before granting program.write.",
        ["program.write"],
      );
    }
    if (lower === ":backend") {
      return result("success", `${this.backend.mode} · ${this.backend.version} · ${this.backend.health}`);
    }
    if (lower === ":where" || lower === "currentaddress") {
      return result(
        "success",
        `quartz-agent :: FUN_004011d0 @ 0x004011d0\ntrace=${this.debugger.trace} snap=${this.debugger.snapshot}`,
        ["program.read"],
      );
    }
    if (lower.startsWith("functions(")) {
      return result(
        "success",
        "FUN_004011d0          0x004011d0\ndispatch_payload      0x00401670\nrotate_key            0x00401b20",
        ["program.read"],
      );
    }
    if (lower.startsWith("bytes(")) {
      return result("success", "48 89 e5 48 83 ec 30 48 8b 47 18 85 c0 74 16 90", ["program.read"]);
    }
    if (this.backend.health !== "connected") {
      const detail = this.backend.health === "detected"
        ? "A Ghidra distribution is detected, but the interactive script host is not connected. No code was executed."
        : "This Ghidra API expression needs the JDK 21 sidecar. No code was executed.";
      return result(
        "backend-unavailable",
        detail,
        ["program.read"],
      );
    }
    return result("success", `Accepted by the read-only sidecar preview:\n${expression}`, ["program.read"]);
  }

  setExtensionEnabled(id: string, enabled: boolean, approvals: Capability[] = []): ExtensionState {
    const index = this.extensions.findIndex((extension) => extension.id === id);
    if (index < 0) throw new Error(`Unknown extension: ${id}`);
    const current = this.extensions[index]!;
    let next: ExtensionState;
    if (!enabled) {
      next = { ...current, enabled: false, status: "Disabled" };
    } else if (current.requiresBackend && this.backend.health !== "connected") {
      next = {
        ...current,
        enabled: false,
        status: this.backend.health === "detected"
          ? "Distribution detected · interactive adapter not connected"
          : "Requires a connected Ghidra sidecar",
      };
    } else if (!current.requiredCapabilities.every((capability) => approvals.includes(capability))) {
      next = {
        ...current,
        enabled: false,
        status: `Awaiting capability approval: ${current.requiredCapabilities.join(", ")}`,
      };
    } else {
      next = { ...current, enabled: true, status: "Active" };
    }
    this.extensions[index] = next;
    return next;
  }

  private createExtensions(): ExtensionState[] {
    const connected = this.backend.health === "connected";
    const detected = this.backend.health === "detected";
    return [
      {
        id: "ghidra-headless",
        name: "Ghidra Headless Adapter",
        provider: "GhidraEx",
        enabled: connected,
        requiresBackend: true,
        requiredCapabilities: ["program.read", "filesystem.read"],
        status: connected
          ? "Connected"
          : detected
            ? "Distribution detected · batch bridge available outside this UI"
            : "GHIDRA_HOME not configured",
      },
      {
        id: "trace-ui",
        name: "Debugger Trace & Emulator UI",
        provider: "GhidraEx built-in",
        enabled: true,
        requiresBackend: false,
        requiredCapabilities: ["debug.trace.read"],
        status: "Active · captured trace fixture",
      },
      {
        id: "script-console",
        name: "Capability-gated Script Console",
        provider: "GhidraEx built-in",
        enabled: true,
        requiresBackend: false,
        requiredCapabilities: ["program.read"],
        status: "Active · read-only preview",
      },
      {
        id: "bsim-client",
        name: "BSim Similarity Client",
        provider: "Optional Ghidra capability",
        enabled: false,
        requiresBackend: true,
        requiredCapabilities: ["program.read", "network"],
        status: "Disabled · network permission required",
      },
    ];
  }
}

function result(status: ScriptResult["status"], output: string, requiredCapabilities: Capability[] = []): ScriptResult {
  return { status, output, requiredCapabilities };
}

function containsAny(source: string, ...needles: string[]): boolean {
  return needles.some((needle) => source.includes(needle));
}
