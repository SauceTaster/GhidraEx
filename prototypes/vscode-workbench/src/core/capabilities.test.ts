import { describe, expect, it } from 'vitest';
import { capabilityReport, resolveBackendCapabilities } from './capabilities';

describe('backend capability resolution', () => {
  it('marks the deterministic engine ready without claiming real Ghidra', () => {
    const snapshot = resolveBackendCapabilities({
      selectedMode: 'synthetic',
      hostKind: 'desktop',
      workspaceTrusted: true,
    });
    expect(snapshot).toMatchObject({
      selectedMode: 'synthetic',
      effectiveMode: 'synthetic',
      realBackendReady: false,
      fallbackActive: false,
      backend: { state: 'ready' },
    });
    expect(snapshot.capabilities.find(item => item.id === 'capability.debugger')).toMatchObject({ state: 'limited' });
  });

  it('explains why a browser host cannot launch a local JVM', () => {
    const snapshot = resolveBackendCapabilities({
      selectedMode: 'local',
      hostKind: 'web',
      workspaceTrusted: true,
      localGhidraPath: '/opt/ghidra',
    });
    expect(snapshot.backend).toMatchObject({ state: 'unavailable' });
    expect(snapshot.backend.detail).toContain('Browser extension hosts cannot launch Java');
    expect(snapshot.fallbackActive).toBe(true);
  });

  it('distinguishes an unconfigured local backend from a configured but missing adapter', () => {
    const missing = resolveBackendCapabilities({
      selectedMode: 'local',
      hostKind: 'desktop',
      workspaceTrusted: true,
    });
    const configured = resolveBackendCapabilities({
      selectedMode: 'local',
      hostKind: 'desktop',
      workspaceTrusted: true,
      localGhidraPath: '/opt/ghidra',
    });
    expect(missing.backend.state).toBe('unconfigured');
    expect(configured.backend.state).toBe('unavailable');
    expect(configured.backend.detail).toContain('does not yet ship the JVM bridge');
  });

  it('requires workspace trust for a local process bridge', () => {
    const snapshot = resolveBackendCapabilities({
      selectedMode: 'local',
      hostKind: 'desktop',
      workspaceTrusted: false,
      localGhidraPath: '/opt/ghidra',
    });
    expect(snapshot.backend).toMatchObject({ state: 'unavailable' });
    expect(snapshot.backend.detail).toContain('Workspace trust');
  });

  it('requires a valid HTTPS remote endpoint and still exposes the missing transport', () => {
    const missing = resolveBackendCapabilities({
      selectedMode: 'remote',
      hostKind: 'web',
      workspaceTrusted: true,
    });
    const insecure = resolveBackendCapabilities({
      selectedMode: 'remote',
      hostKind: 'web',
      workspaceTrusted: true,
      remoteEndpoint: 'http://localhost:8080',
    });
    const configured = resolveBackendCapabilities({
      selectedMode: 'remote',
      hostKind: 'web',
      workspaceTrusted: true,
      remoteEndpoint: 'https://ghidra.example.test',
    });
    const credentialsInUrl = resolveBackendCapabilities({
      selectedMode: 'remote',
      hostKind: 'web',
      workspaceTrusted: true,
      remoteEndpoint: 'https://token@ghidra.example.test',
    });
    expect(missing.backend.state).toBe('unconfigured');
    expect(insecure.backend.detail).toContain('credential-free HTTPS URL');
    expect(credentialsInUrl.backend.detail).toContain('credential-free HTTPS URL');
    expect(configured.backend.detail).toContain('transport adapter is not implemented');
    expect(configured.realBackendReady).toBe(false);
  });

  it('renders a copyable report with backend, capability, and plugin truth', () => {
    const report = capabilityReport(resolveBackendCapabilities({
      selectedMode: 'remote',
      hostKind: 'web',
      workspaceTrusted: true,
      remoteEndpoint: 'https://ghidra.example.test',
    }));
    expect(report).toContain('Requested backend: remote');
    expect(report).toContain('Real Ghidra backend ready: no');
    expect(report).toContain('Capabilities:');
    expect(report).toContain('Plugins:');
  });
});
