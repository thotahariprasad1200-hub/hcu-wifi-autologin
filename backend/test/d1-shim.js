// Test-only D1 stand-in backed by Node's built-in SQLite (the same engine D1 runs).
// It mimics the parts of the D1 API the Worker uses: prepare().bind().first()/all()/run() and
// batch() as one transaction (all statements commit together or roll back together).

import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';

class BoundStatement {
  constructor(sqlite, sql, params = []) {
    this.sqlite = sqlite;
    this.sql = sql;
    this.params = params;
  }

  bind(...params) {
    return new BoundStatement(this.sqlite, this.sql, params.map((p) => (p === undefined ? null : p)));
  }

  execute() {
    const statement = this.sqlite.prepare(this.sql);
    if (/^\s*select/i.test(this.sql) || /\breturning\b/i.test(this.sql)) {
      return { success: true, results: statement.all(...this.params), meta: { changes: 0 } };
    }
    const outcome = statement.run(...this.params);
    return { success: true, results: [], meta: { changes: Number(outcome.changes) } };
  }

  async first() { return this.execute().results[0] ?? null; }
  async all() { return this.execute(); }
  async run() { return this.execute(); }
}

export class D1Shim {
  constructor() {
    this.sqlite = new DatabaseSync(':memory:');
    this.sqlite.exec(readFileSync(new URL('../schema.sql', import.meta.url), 'utf8'));
    this.failNextBatch = false;
  }

  prepare(sql) { return new BoundStatement(this.sqlite, sql); }

  async batch(statements) {
    if (this.failNextBatch) {
      this.failNextBatch = false;
      throw new Error('simulated D1 outage');
    }
    this.sqlite.exec('BEGIN');
    try {
      const results = statements.map((s) => s.execute());
      this.sqlite.exec('COMMIT');
      return results;
    } catch (err) {
      this.sqlite.exec('ROLLBACK');
      throw err;
    }
  }

  // Convenience for assertions.
  rows(sql, ...params) { return this.sqlite.prepare(sql).all(...params); }
}
