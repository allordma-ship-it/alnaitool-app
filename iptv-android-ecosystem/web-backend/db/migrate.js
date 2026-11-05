import { loadConfig } from '../config.js';
import { createDb } from './index.js';

const config = loadConfig();
const db = await createDb(config);
await db.migrate();
await db.close();
console.log(`Migration complete (${config.databaseUrl ? 'postgres' : `sqlite: ${config.sqlitePath}`})`);
