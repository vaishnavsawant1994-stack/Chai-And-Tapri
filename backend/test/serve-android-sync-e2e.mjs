import { createServer as createHttpsServer } from 'node:https';
import { readFile } from 'node:fs/promises';
import { createPool } from '../src/db.mjs';
import { createApiServer } from '../src/http.mjs';
import { SyncService } from '../src/service.mjs';

const port = Number(process.env.SYNC_E2E_PORT ?? 8793);
const dropAckOnSyncRequest = Number(process.env.SYNC_E2E_DROP_ACK_ON_SYNC_REQUEST ?? 0);
const holdAckOnSyncRequest = Number(process.env.SYNC_E2E_HOLD_ACK_ON_SYNC_REQUEST ?? 0);
const controlToken = process.env.SYNC_E2E_CONTROL_TOKEN;
const pfxPath = process.env.SYNC_E2E_PFX_PATH;
const passphrase = process.env.SYNC_E2E_PFX_PASSPHRASE;
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('SYNC_E2E_PORT is invalid');
if (!Number.isInteger(dropAckOnSyncRequest) || dropAckOnSyncRequest < 0) throw new Error('SYNC_E2E_DROP_ACK_ON_SYNC_REQUEST is invalid');
if (!Number.isInteger(holdAckOnSyncRequest) || holdAckOnSyncRequest < 0) throw new Error('SYNC_E2E_HOLD_ACK_ON_SYNC_REQUEST is invalid');
if (holdAckOnSyncRequest > 0 && (!controlToken || controlToken.length < 24)) throw new Error('A 24+ character SYNC_E2E_CONTROL_TOKEN is required when holding acknowledgements');
if (!pfxPath || !passphrase) throw new Error('A test-only PKCS#12 file and passphrase are required');

const pool = createPool();
const api = createApiServer(new SyncService(pool));
const handler = api.listeners('request')[0];
const pfx = await readFile(pfxPath);
let syncRequestCount = 0;
const heldAcks = new Map();
const loopback = (address = '') => address === '127.0.0.1' || address === '::1' || address === '::ffff:127.0.0.1';
const sendControl = (res, status, payload) => {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  res.end(JSON.stringify(payload));
};
const server = createHttpsServer({ pfx, passphrase }, (req, res) => {
  const requestUrl = new URL(req.url, 'https://localhost');
  if (requestUrl.pathname.startsWith('/__test/')) {
    if (!loopback(req.socket.remoteAddress) || !controlToken || req.headers['x-test-control-token'] !== controlToken) {
      sendControl(res, 404, { error: 'not_found' });
      return;
    }
    if (req.method === 'GET' && requestUrl.pathname === '/__test/status') {
      sendControl(res, 200, { syncRequestCount, heldAckCount: heldAcks.size, heldAckRequestNumbers: [...heldAcks.keys()] });
      return;
    }
    if (req.method === 'POST' && requestUrl.pathname === '/__test/reset') {
      if (heldAcks.size > 0) {
        sendControl(res, 409, { error: 'acknowledgment_still_held' });
        return;
      }
      syncRequestCount = 0;
      sendControl(res, 200, { reset: true });
      return;
    }
    if (req.method === 'POST' && requestUrl.pathname === '/__test/release-held-acks') {
      const pending = [...heldAcks.keys()];
      const releases = [...heldAcks.values()];
      heldAcks.clear();
      releases.forEach(release => release());
      sendControl(res, 200, { releasedAckCount: pending.length });
      return;
    }
    sendControl(res, 404, { error: 'not_found' });
    return;
  }
  const requestNumber = req.method === 'POST' && new URL(req.url, 'https://localhost').pathname === '/v1/sync/events'
    ? ++syncRequestCount
    : 0;
  if (dropAckOnSyncRequest > 0 && requestNumber === dropAckOnSyncRequest) {
    res.end = function (...args) {
      console.log(`Synthetic response-loss fault after sync request ${requestNumber} committed.`);
      res.socket?.destroy();
      return res;
    };
  }
  if (holdAckOnSyncRequest > 0 && requestNumber === holdAckOnSyncRequest) {
    const end = res.end;
    res.end = function (...args) {
      res.end = end;
        console.log(`Holding acknowledgment after synthetic sync request ${requestNumber} committed.`);
      heldAcks.set(requestNumber, () => end.apply(res, args));
      return res;
    };
  }
  handler(req, res);
});
server.listen(port, '127.0.0.1', () => console.log(`Synthetic Android sync backend listening on 127.0.0.1:${port}`));

for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
  [...heldAcks.values()].forEach(release => release());
  heldAcks.clear();
  server.close(async () => {
    await pool.end();
    process.exit(0);
  });
});
