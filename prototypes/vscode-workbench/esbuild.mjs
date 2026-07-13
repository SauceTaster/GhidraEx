import { build } from 'esbuild';
import { mkdir, rm } from 'node:fs/promises';

const production = process.argv.includes('--production');

await rm('dist', { recursive: true, force: true });
await mkdir('dist/node', { recursive: true });
await mkdir('dist/web', { recursive: true });
await mkdir('dist/web-test', { recursive: true });

const shared = {
  bundle: true,
  sourcemap: production ? false : 'external',
  minify: production,
  logLevel: 'info',
  external: ['vscode'],
};

await Promise.all([
  build({
    ...shared,
    entryPoints: ['src/extension.ts'],
    outfile: 'dist/node/extension.js',
    platform: 'node',
    format: 'cjs',
    target: 'node20',
  }),
  build({
    ...shared,
    entryPoints: ['src/extension.ts'],
    outfile: 'dist/web/extension.js',
    platform: 'browser',
    format: 'cjs',
    target: 'es2022',
  }),
  build({
    ...shared,
    entryPoints: ['src/test/web-host.ts'],
    outfile: 'dist/web-test/index.js',
    platform: 'browser',
    format: 'cjs',
    target: 'es2022',
  }),
]);
