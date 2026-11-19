import { createPool } from './db.mjs';
import { SyncService } from './service.mjs';
import { createApiServer } from './http.mjs';

const pool = createPool();
const server = createApiServer(new SyncService(pool));
const port = Number(process.env.PORT ?? 8787);
const host = process.env.HOST ?? '127.0.0.1';
server.listen(port, host, () => console.log(`Sync journal listening on ${host}:${port}`));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(async () => { await pool.end(); process.exit(0); }));
