import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { resolve } from 'node:path';
const mode = process.argv[2];
if (!['start', 'test'].includes(mode) || process.argv.length !== 3) {
  throw new Error('start/test만 허용합니다. deploy와 임의 CLI 인자는 금지합니다.');
}
const cwd = fileURLToPath(new URL('..', import.meta.url));
const guard = resolve(cwd, 'scripts/local-network-only.cjs').split(String.fromCharCode(92)).join('/');
const env = { ...process.env, CI: 'true', METADATA_SERVER_DETECTION: 'none', FIREBASE_CLI_DISABLE_UPDATE_CHECK: 'true',
  FIRESTORE_EMULATOR_HOST: '127.0.0.1:8080', FIREBASE_AUTH_EMULATOR_HOST: '127.0.0.1:9099',
  GCLOUD_PROJECT: 'mobokfestivalpub', GOOGLE_CLOUD_PROJECT: 'mobokfestivalpub',
  NODE_OPTIONS: `--require="${guard}"`, JAVA_TOOL_OPTIONS: '-Xmx512m' };
for (const key of Object.keys(env)) if (/proxy/i.test(key) || ['GOOGLE_APPLICATION_CREDENTIALS', 'FIREBASE_TOKEN'].includes(key)) delete env[key];
const args = [resolve(cwd, 'node_modules/firebase-tools/lib/bin/firebase.js'),
  mode === 'start' ? 'emulators:start' : 'emulators:exec', '--only', 'firestore,auth',
  '--project', 'mobokfestivalpub', '--config', 'firebase.emulator.json'];
if (mode === 'test') args.push('node --test --test-concurrency=1 test/*.test.mjs');
const child = spawn(process.execPath, args, { cwd, env, stdio: 'inherit', windowsHide: true });
child.on('exit', code => { process.exitCode = code ?? 1; });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
