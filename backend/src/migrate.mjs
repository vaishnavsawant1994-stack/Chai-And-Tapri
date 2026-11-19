import { readFile, readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { createPool, withTransaction } from './db.mjs';

const pool = createPool();
try {
  const directory = fileURLToPath(new URL('../migrations/', import.meta.url));
  const migrations = (await readdir(directory)).filter((name) => /^\d{3}_.+\.sql$/.test(name)).sort();
  for (const filename of migrations) {
    const version = filename.slice(0, -4);
    const sql = await readFile(new URL(`../migrations/${filename}`, import.meta.url), 'utf8');
    const applied = await withTransaction(pool, async (client) => {
      const table = await client.query("SELECT to_regclass('public.schema_migrations') IS NOT NULL AS exists");
      if (table.rows[0].exists) {
        const known = await client.query('SELECT 1 FROM schema_migrations WHERE version=$1', [version]);
        if (known.rowCount) return false;
      } else if (version !== migrations[0].slice(0, -4)) {
        throw new Error('Initial schema migration must be applied before later migrations.');
      }
      await client.query(sql);
      await client.query('INSERT INTO schema_migrations(version) VALUES ($1) ON CONFLICT DO NOTHING', [version]);
      return true;
    });
    if (applied) console.log(`Applied backend migration ${version}`);
  }
} finally {
  await pool.end();
}
