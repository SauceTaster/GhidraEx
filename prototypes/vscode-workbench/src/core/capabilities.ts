export type BackendMode = 'synthetic' | 'local' | 'remote';
export type ExtensionHostKind = 'desktop' | 'web';
export type CapabilityState = 'ready' | 'limited' | 'unconfigured' | 'unavailable';

export interface CapabilityEnvironment {
  readonly selectedMode: BackendMode;
  readonly hostKind: ExtensionHostKind;
  readonly workspaceTrusted: boolean;
  readonly localGhidraPath?: string;
  readonly remoteEndpoint?: string;
}

export interface CapabilityRecord {
  readonly id: string;
  readonly title: string;
  readonly state: CapabilityState;
  readonly detail: string;
}

export interface PluginCapabilityRecord extends CapabilityRecord {
  readonly kind: 'builtin' | 'bridge' | 'ghidra-extension';
}

export interface BackendCapabilitySnapshot {
  readonly selectedMode: BackendMode;
  readonly effectiveMode: 'synthetic';
  readonly hostKind: ExtensionHostKind;
  readonly workspaceTrusted: boolean;
  readonly realBackendReady: false;
  readonly fallbackActive: boolean;
  readonly backend: CapabilityRecord;
  readonly capabilities: readonly CapabilityRecord[];
  readonly plugins: readonly PluginCapabilityRecord[];
}

function configured(value: string | undefined): boolean {
  return value !== undefined && value.trim().length > 0;
}

function remoteEndpointIsSecure(value: string | undefined): boolean {
  if (!configured(value)) return false;
  try {
    const endpoint = new URL(value!);
    return endpoint.protocol === 'https:' && endpoint.username.length === 0 && endpoint.password.length === 0;
  } catch {
    return false;
  }
}

function requestedBackend(environment: CapabilityEnvironment): CapabilityRecord {
  if (environment.selectedMode === 'synthetic') {
    return {
      id: 'backend.synthetic',
      title: 'Deterministic prototype engine',
      state: 'ready',
      detail: 'In-process TypeScript fixture; it is not a Ghidra JVM or live target.',
    };
  }

  if (environment.selectedMode === 'local') {
    if (environment.hostKind === 'web') {
      return {
        id: 'backend.local',
        title: 'Local Ghidra JVM bridge',
        state: 'unavailable',
        detail: 'Browser extension hosts cannot launch Java or access an arbitrary local Ghidra installation.',
      };
    }
    if (!environment.workspaceTrusted) {
      return {
        id: 'backend.local',
        title: 'Local Ghidra JVM bridge',
        state: 'unavailable',
        detail: 'Workspace trust is required before an extension may launch or connect to a local JVM.',
      };
    }
    if (!configured(environment.localGhidraPath)) {
      return {
        id: 'backend.local',
        title: 'Local Ghidra JVM bridge',
        state: 'unconfigured',
        detail: 'Set ghidraex.engine.localPath to a Ghidra installation; the transport adapter is still pending.',
      };
    }
    return {
      id: 'backend.local',
      title: 'Local Ghidra JVM bridge',
      state: 'unavailable',
      detail: 'A Ghidra path is configured, but this prototype does not yet ship the JVM bridge process.',
    };
  }

  if (!configured(environment.remoteEndpoint)) {
    return {
      id: 'backend.remote',
      title: 'Remote Ghidra HTTPS bridge',
      state: 'unconfigured',
      detail: 'Set ghidraex.engine.endpoint to an authenticated HTTPS service.',
    };
  }
  if (!remoteEndpointIsSecure(environment.remoteEndpoint)) {
    return {
      id: 'backend.remote',
      title: 'Remote Ghidra HTTPS bridge',
      state: 'unavailable',
      detail: 'The endpoint must be a valid credential-free HTTPS URL; credentials must come from VS Code authentication or secret storage.',
    };
  }
  return {
    id: 'backend.remote',
    title: 'Remote Ghidra HTTPS bridge',
    state: 'unavailable',
    detail: 'An HTTPS endpoint is configured, but the authenticated transport adapter is not implemented yet.',
  };
}

export function resolveBackendCapabilities(environment: CapabilityEnvironment): BackendCapabilitySnapshot {
  const backend = requestedBackend(environment);
  const fallbackActive = environment.selectedMode !== 'synthetic';
  const syntheticDetail = fallbackActive
    ? `The ${environment.selectedMode} backend is not ready, so read-only prototype operations use the synthetic engine.`
    : 'Backed by the deterministic in-process fixture.';

  return {
    selectedMode: environment.selectedMode,
    effectiveMode: 'synthetic',
    hostKind: environment.hostKind,
    workspaceTrusted: environment.workspaceTrusted,
    realBackendReady: false,
    fallbackActive,
    backend,
    capabilities: [
      {
        id: 'capability.program-model',
        title: 'Program model and navigation',
        state: fallbackActive ? 'limited' : 'ready',
        detail: syntheticDetail,
      },
      {
        id: 'capability.analysis',
        title: 'Analyzer pipeline',
        state: fallbackActive ? 'limited' : 'ready',
        detail: `${syntheticDetail} No Ghidra analyzers are loaded.`,
      },
      {
        id: 'capability.debugger',
        title: 'VS Code debug session',
        state: 'limited',
        detail: 'Native Debug Adapter Protocol session over a deterministic trace; no live Ghidra Debugger/TraceRmi target.',
      },
      {
        id: 'capability.repl',
        title: 'Script console',
        state: 'limited',
        detail: 'The native terminal runs bounded gx commands. Python, Java, and GhidraScript require a real bridge.',
      },
      {
        id: 'capability.mutations',
        title: 'Program mutations and transactions',
        state: 'unavailable',
        detail: 'Rename, type, patch, memory, and transaction writes remain disabled until a real backend exists.',
      },
    ],
    plugins: [
      {
        id: 'plugin.core-model',
        title: 'Core listing/decompiler model',
        kind: 'builtin',
        state: 'ready',
        detail: 'Bundled deterministic provider used by the native editors and TreeViews.',
      },
      {
        id: 'plugin.debug-trace',
        title: 'Synthetic debugger trace',
        kind: 'builtin',
        state: 'limited',
        detail: 'Bundled DAP adapter for UI and workflow testing; it does not control a process.',
      },
      {
        id: 'plugin.jvm-bridge',
        title: 'Ghidra JVM bridge',
        kind: 'bridge',
        state: backend.id === 'backend.local' ? backend.state : 'unconfigured',
        detail: 'Required for local Ghidra APIs, headless analysis, Java/Python scripts, and extension discovery.',
      },
      {
        id: 'plugin.remote-bridge',
        title: 'Remote Ghidra service',
        kind: 'bridge',
        state: backend.id === 'backend.remote' ? backend.state : 'unconfigured',
        detail: 'Required by browser hosts and optional for isolated desktop deployments.',
      },
      {
        id: 'plugin.ghidra-extensions',
        title: 'Ghidra extension loader',
        kind: 'ghidra-extension',
        state: 'unavailable',
        detail: 'Installed Ghidra extensions cannot be enumerated or changed without a connected real backend.',
      },
    ],
  };
}

export function capabilityReport(snapshot: BackendCapabilitySnapshot): string {
  const lines = [
    `Requested backend: ${snapshot.selectedMode}`,
    `Effective backend: ${snapshot.effectiveMode}${snapshot.fallbackActive ? ' (fallback)' : ''}`,
    `Extension host: ${snapshot.hostKind}`,
    `Workspace trusted: ${snapshot.workspaceTrusted ? 'yes' : 'no'}`,
    `Real Ghidra backend ready: ${snapshot.realBackendReady ? 'yes' : 'no'}`,
    '',
    `Backend — ${snapshot.backend.state}: ${snapshot.backend.title}`,
    `  ${snapshot.backend.detail}`,
    '',
    'Capabilities:',
    ...snapshot.capabilities.map(item => `- ${item.state}: ${item.title} — ${item.detail}`),
    '',
    'Plugins:',
    ...snapshot.plugins.map(item => `- ${item.state}: ${item.title} [${item.kind}] — ${item.detail}`),
  ];
  return lines.join('\n');
}
