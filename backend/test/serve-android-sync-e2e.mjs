import { createServer as createHttpsServer } from 'node:https';
import { readFile } from 'node:fs/promises';
import { createPool } from '../src/db.mjs';
import { createApiServer } from '../src/http.mjs';
import { SyncService } from '../src/service.mjs';

const port = Number(process.env.SYNC_E2E_PORT ?? 8793);
const pfxPath = process.env.SYNC_E2E_PFX_PATH;
const passphrase = process.env.SYNC_E2E_PFX_PASSPHRASE;
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('SYNC_E2E_PORT is invalid');
if (!pfxPath || !passphrase) throw new Error('A test-only PKCS#12 file and passphrase are required');

const pool = createPool();
const api = createApiServer(new SyncService(pool));
const handler = api.listeners('request')[0];
const pfx = await readFile(pfxPath);
const server = createHttpsServer({ pfx, passphrase }, handler);
server.listen(port, '127.0.0.1', () => console.log(`Synthetic Android sync backend listening on 127.0.0.1:${port}`));

for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(async () => {
  await pool.end();
  process.exit(0);
}));
