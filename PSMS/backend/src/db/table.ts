/**
 * 轻量表访问层（thin table accessor）。
 *
 * 定位说明（重要，避免误解）：
 *   这不是 ORM，也不隐藏 SQL —— 它只把「字段名映射 + 参数化 WHERE + 排序分页」
 *   这三件重复度极高的事收敛到一处，保证**所有**简单读写都是参数化 SQL，
 *   而不是在业务代码里拼字符串。复杂查询（聚合、联表、状态守卫更新）
 *   一律在仓储/服务里写显式 SQL，不走这里。
 *
 * 为什么保留 find/findById/findByIdAndUpdate 这类方法名：
 *   便于从文档模型时代平滑迁移 —— 业务规则、状态机、审计逻辑完全不动，
 *   只把数据访问换成真实的关系库读写，降低这次改造的回归风险。
 */
import type { PoolClient } from 'pg';
import { connectClient, getPool } from './openGaussClient.js';
import { logger } from '../lib/logger.js';

export type Filter = Record<string, unknown>;

/**
 * 子表（一对多）描述。
 * 原文档模型里的内嵌数组在关系库里落到子表，这里把「怎么落、怎么读回来」声明清楚。
 */
export interface ChildSpec {
  /** 挂在父对象上的 API 字段名 */
  key: string;
  /** 子表物理名 */
  table: string;
  /** 指向父表的外键列 */
  foreignKey: string;
  /** 子表列（API 名 → 列名） */
  columns: Record<string, string>;
  /** 子表主键列（用于 ON CONFLICT 目标） */
  keyColumns: string[];
  /** 序号列（存在时由本层自动编号） */
  seqColumn?: string;
  /** 读取排序表达式 */
  orderBy: string;
  /** 需要转数字的子表字段 */
  numericColumns?: string[];
  /**
   * 读回时把子表项转换成对外形态（如 task_dependencies 的 {dependsOnTaskId} → 字符串）。
   * 缺省直接使用子表项的 API 对象。
   */
  transform?: (items: Record<string, unknown>[]) => unknown;
}

export interface TableSpec {
  /** 物理表名 */
  table: string;
  /** API 字段名 → 列名（允许多个 API 名指向同一列，如 _id 与 id） */
  columns: Record<string, string>;
  /** 主键列名，默认 id */
  primaryKey?: string;
  /** jsonb 列（写出时序列化；读入由 pg 自动反序列化） */
  jsonColumns?: string[];
  /** numeric 列（读出转 number，避免前端拿到 "12.000"） */
  numericColumns?: string[];
  /**
   * 写入前把「嵌套字段」摊平成列，例如 supplierInfo → supplierName/supplierContact/supplierPhone。
   * 摊平后应把嵌套键删除，避免它又被当普通列处理。
   */
  beforeWrite?: (doc: Record<string, unknown>) => Record<string, unknown>;
  /** 读后装配子表与嵌套结构 */
  hydrate?: (rows: Record<string, unknown>[]) => Promise<void>;
  /** 写入后落子表 */
  persistChildren?: (
    ids: string[],
    docs: Record<string, unknown>[],
    client: PoolClient,
  ) => Promise<void>;
  /**
   * 由子表承载的多值字段。
   * 声明后：插入时跳过这些键（交给 persistChildren）、
   * 且 findByIdAndUpdate 支持对它们做"整体替换"。
   */
  children?: ChildSpec[];
}

interface SortSpec {
  [apiField: string]: 1 | -1;
}

export interface UpdateOptions {
  new?: boolean;
}

const IDENT = /^[a-z_][a-z0-9_]*$/;

/** 多值字段名集合（由子表承载，不参与列写入） */
function childKeys(spec: TableSpec): Set<string> {
  return new Set((spec.children ?? []).map((c) => c.key));
}

/** 批量把子表行挂到父对象上（避免 N+1） */
export function makeHydrate(child: ChildSpec, parentIdField = '_id') {
  return async (rows: Record<string, unknown>[]): Promise<void> => {
    const ids = rows.map((r) => String(r[parentIdField]));
    if (ids.length === 0) return;
    const res = await getPool().query<Record<string, unknown>>(
      `SELECT * FROM ${child.table} WHERE ${child.foreignKey} = ANY($1::text[]) ORDER BY ${child.orderBy}`,
      [ids] as never[],
    );
    const bucket = new Map<string, Record<string, unknown>[]>();
    for (const row of res.rows) {
      const owner = String(row[child.foreignKey]);
      const item: Record<string, unknown> = {};
      for (const [apiField, column] of Object.entries(child.columns)) {
        const value = row[column];
        item[apiField] = child.numericColumns?.includes(apiField) && value !== null
          ? Number(value)
          : value;
      }
      const list = bucket.get(owner) ?? [];
      list.push(item);
      bucket.set(owner, list);
    }
    for (const row of rows) {
      const items = bucket.get(String(row[parentIdField])) ?? [];
      row[child.key] = child.transform ? child.transform(items) : items;
    }
  };
}

/** 取出父 doc 上的子表数组（并允许调用方先做形态转换） */
function childItemsOf(
  child: ChildSpec,
  doc: Record<string, unknown>,
): Record<string, unknown>[] {
  const raw = doc[child.key];
  if (!Array.isArray(raw) || raw.length === 0) return [];
  return raw.map((item) =>
    typeof item === 'object' && item !== null ? (item as Record<string, unknown>) : { value: item },
  );
}

/**
 * 把子表行插入到指定父记录下。
 *
 * 注意：openGauss 6.0 **不支持 PostgreSQL 的 `ON CONFLICT ... DO NOTHING`**
 * （实测报 syntax error at or near "CONFLICT"），因此这里用普通 INSERT。
 * 语义上也不需要 upsert —— 新增父记录时子表必为空；
 * 更新路径会先删旧子行再插入（见 Table.findByIdAndUpdate）。
 */
export async function insertChildRows(
  client: PoolClient,
  child: ChildSpec,
  ownerId: string,
  items: Record<string, unknown>[],
): Promise<void> {
  for (const [seq, item] of items.entries()) {
    const columns: string[] = [child.foreignKey];
    const params: unknown[] = [ownerId];
    const holders: string[] = ['$1'];

    if (child.seqColumn) {
      columns.push(child.seqColumn);
      params.push(seq + 1);
      holders.push(`$${params.length}`);
    }

    for (const [apiField, column] of Object.entries(child.columns)) {
      if (apiField === child.key) continue;
      const value = item[apiField];
      if (value === undefined) continue;
      columns.push(column);
      params.push(value === null || value === '' ? null : value);
      holders.push(`$${params.length}`);
    }

    await client.query(
      `INSERT INTO ${child.table} (${columns.join(', ')})
       VALUES (${holders.join(', ')})`,
      params as never[],
    );
  }
}


/** 把 API 字段名解析为列名；未声明的字段直接报错，避免静默写错列 */
function columnOf(spec: TableSpec, apiField: string): string {
  const column = spec.columns[apiField];
  if (!column) {
    throw new Error(`表 ${spec.table} 未声明字段映射: ${apiField}`);
  }
  if (!IDENT.test(column)) {
    throw new Error(`表 ${spec.table} 的列名非法: ${column}`);
  }
  return column;
}

interface BuiltWhere {
  sql: string;
  params: unknown[];
}

/** 把筛选对象翻译成参数化 WHERE 子句（全部值走占位符） */
function buildWhere(spec: TableSpec, filter: Filter | undefined): BuiltWhere {
  const parts: string[] = [];
  const params: unknown[] = [];

  const push = (fragment: (ph: string[]) => string, values: unknown[]) => {
    const placeholders = values.map((v) => {
      params.push(v);
      return `$${params.length}`;
    });
    parts.push(fragment(placeholders));
  };

  for (const [apiField, raw] of Object.entries(filter ?? {})) {
    if (raw === undefined) continue;
    const col = columnOf(spec, apiField);

    if (raw !== null && typeof raw === 'object' && !(raw instanceof Date)) {
      const ops = raw as Record<string, unknown>;
      const handled = new Set<string>();

      if ('$in' in ops) {
        const list = (ops.$in as unknown[]) ?? [];
        handled.add('$in');
        if (list.length === 0) {
          parts.push('FALSE'); // 空集合语义上匹配不到任何行
          continue;
        }
        push(([ph]) => `${col} = ANY(${ph}::text[])`, [list.map(String)]);
      }
      if ('$nin' in ops) {
        const list = (ops.$nin as unknown[]) ?? [];
        handled.add('$nin');
        if (list.length > 0) {
          push(([ph]) => `NOT (${col} = ANY(${ph}::text[]))`, [list.map(String)]);
        }
      }
      if ('$ne' in ops) {
        handled.add('$ne');
        if (ops.$ne === null) {
          parts.push(`${col} IS NOT NULL`);
        } else {
          push(([ph]) => `${col} <> ${ph}`, [ops.$ne]);
        }
      }
      if ('$regex' in ops) {
        handled.add('$regex');
        const flags = String(ops.$options ?? '');
        const op = flags.includes('i') ? 'ILIKE' : 'LIKE';
        push(([ph]) => `${col} ${op} ${ph}`, [`%${String(ops.$regex)}%`]);
      }
      for (const [op, sqlOp] of [
        ['$gte', '>='],
        ['$lte', '<='],
        ['$gt', '>'],
        ['$lt', '<'],
      ] as const) {
        if (op in ops) {
          handled.add(op);
          push(([ph]) => `${col} ${sqlOp} ${ph}`, [ops[op]]);
        }
      }
      if ('$exists' in ops) {
        handled.add('$exists');
        parts.push(ops.$exists ? `${col} IS NOT NULL` : `${col} IS NULL`);
      }
      const extra = Object.keys(ops).filter((k) => !handled.has(k));
      if (extra.length > 0) {
        throw new Error(`表 ${spec.table} 暂不支持的查询操作符: ${extra.join(', ')}`);
      }
      continue;
    }

    if (raw === null) {
      parts.push(`${col} IS NULL`);
    } else {
      push(([ph]) => `${col} = ${ph}`, [raw]);
    }
  }

  return {
    sql: parts.length > 0 ? `WHERE ${parts.join(' AND ')}` : '',
    params,
  };
}

function buildOrderBy(spec: TableSpec, sort?: SortSpec): string {
  if (!sort) return '';
  const parts = Object.entries(sort).map(
    ([field, dir]) => `${columnOf(spec, field)} ${dir === -1 ? 'DESC' : 'ASC'}`,
  );
  return parts.length > 0 ? `ORDER BY ${parts.join(', ')}` : '';
}

/** 行 → API 对象：还原列名、数值类型，并补上所有别名（如 _id / id） */
function mapRow(spec: TableSpec, row: Record<string, unknown>): Record<string, unknown> {
  const numeric = new Set(spec.numericColumns ?? []);
  const out: Record<string, unknown> = {};
  for (const [apiField, column] of Object.entries(spec.columns)) {
    const value = row[column];
    if (value === undefined) continue;
    out[apiField] = numeric.has(apiField) && value !== null ? Number(value) : value;
  }
  return out;
}

/** 把更新对象转换成 SET 片段 */
function buildSet(
  spec: TableSpec,
  update: Record<string, unknown>,
  startIndex: number,
): { sql: string; params: unknown[] } {
  const json = new Set(spec.jsonColumns ?? []);
  const skip = childKeys(spec);
  const sets: string[] = [];
  const params: unknown[] = [];

  for (const [apiField, value] of Object.entries(update)) {
    if (value === undefined) continue;
    // 多值字段由子表承载，不走 SET（调用方需显式处理）
    if (skip.has(apiField)) continue;
    // $set / $inc 这类 mongo 操作符不在本层支持，出现即报错，避免静默忽略
    if (apiField.startsWith('$')) {
      throw new Error(`表 ${spec.table} 不支持更新操作符 ${apiField}，请写显式 SQL`);
    }
    const col = columnOf(spec, apiField);
    params.push(json.has(apiField) && value !== null ? JSON.stringify(value) : value);
    sets.push(`${col} = $${startIndex + params.length - 1}`);
  }

  if (sets.length === 0) return { sql: '', params: [] };
  return { sql: sets.join(', '), params };
}

/**
 * 允许调用点写 `.lean<Doc>()` 或 `.lean<Doc[]>()` 两种风格（迁移前两种都有），
 * 统一解析为「元素类型」。
 */
type RowOf<X> = X extends Array<infer E> ? E : X;

/**
 * 可链式、可 await 的**列表**查询对象（对应 Mongoose 的 Query<T[]>）。
 *
 * 泛型默认 `any`：迁移期绝大多数调用点不写类型参数，
 * 写了 `.lean<PlanDoc>()` 的调用点则能拿到完整类型检查 —— 与 Mongoose 的手感一致。
 */
class ListQuery<T = any> implements PromiseLike<T[]> {
  private sortSpec?: SortSpec;
  private skipCount?: number;
  private limitCount?: number;

  constructor(
    private readonly spec: TableSpec,
    private readonly filter: Filter | undefined,
  ) {}

  sort(spec: SortSpec): this {
    this.sortSpec = spec;
    return this;
  }

  skip(n: number): this {
    this.skipCount = n;
    return this;
  }

  limit(n: number): this {
    this.limitCount = n;
    return this;
  }

  /** 与 Mongoose 的 lean() 对齐；这里本来就返回纯对象，保留仅为兼容调用点 */
  lean<TNext = T>(): ListQuery<RowOf<TNext>> {
    return this as unknown as ListQuery<RowOf<TNext>>;
  }

  private async execute(): Promise<T[]> {
    const where = buildWhere(this.spec, this.filter);
    const order = buildOrderBy(this.spec, this.sortSpec);
    const params = [...where.params];
    let sql = `SELECT * FROM ${this.spec.table} ${where.sql} ${order}`;

    if (this.limitCount !== undefined) {
      params.push(this.limitCount);
      sql += ` LIMIT $${params.length}`;
    }
    if (this.skipCount) {
      params.push(this.skipCount);
      sql += ` OFFSET $${params.length}`;
    }

    const res = await getPool().query(sql, params as never[]);
    const rows = res.rows.map((row) => mapRow(this.spec, row));
    if (this.spec.hydrate && rows.length > 0) {
      await this.spec.hydrate(rows);
    }
    return rows as unknown as T[];
  }

  then<TResult1 = T[], TResult2 = never>(
    onfulfilled?: ((value: T[]) => TResult1 | PromiseLike<TResult1>) | null,
    onrejected?: ((reason: unknown) => TResult2 | PromiseLike<TResult2>) | null,
  ): Promise<TResult1 | TResult2> {
    return this.execute().then(onfulfilled, onrejected);
  }
}

/** 可 await 的**单条**查询对象（对应 Mongoose 的 Query<T | null>） */
class DocQuery<T = any> implements PromiseLike<T | null> {
  constructor(
    private readonly spec: TableSpec,
    private readonly filter: Filter,
    private readonly loader: () => Promise<T | null>,
  ) {}

  lean<TNext = T>(): DocQuery<RowOf<TNext>> {
    return this as unknown as DocQuery<RowOf<TNext>>;
  }

  /** findByIdAndUpdate 之后链式再取字段的场景，直接复用 loader */
  private async execute(): Promise<T | null> {
    void this.spec;
    void this.filter;
    return this.loader();
  }

  then<TResult1 = T | null, TResult2 = never>(
    onfulfilled?: ((value: T | null) => TResult1 | PromiseLike<TResult1>) | null,
    onrejected?: ((reason: unknown) => TResult2 | PromiseLike<TResult2>) | null,
  ): Promise<TResult1 | TResult2> {
    return this.execute().then(onfulfilled, onrejected);
  }
}

export class Table {
  constructor(private readonly spec: TableSpec) {}

  get tableName(): string {
    return this.spec.table;
  }

  find<T = any>(filter?: Filter): ListQuery<T> {
    return new ListQuery<T>(this.spec, filter);
  }

  /** 取单条：与 find 共用实现，但只回一条并摊平为对象 */
  findOne<T = any>(filter?: Filter): DocQuery<T> {
    return new DocQuery<T>(this.spec, filter ?? {}, () => this.loadOne(filter));
  }

  findById<T = any>(id: string): DocQuery<T> {
    return new DocQuery<T>(this.spec, { _id: id }, () => this.loadOne({ _id: id }));
  }

  private async loadOne(filter?: Filter): Promise<any> {
    const rows = await new ListQuery(this.spec, filter).limit(1);
    return rows[0] ?? null;
  }

  async countDocuments(filter?: Filter): Promise<number> {
    const where = buildWhere(this.spec, filter);
    const res = await getPool().query<{ n: string }>(
      `SELECT COUNT(*) AS n FROM ${this.spec.table} ${where.sql}`,
      where.params as never[],
    );
    return Number(res.rows[0]?.n ?? 0);
  }

  /**
   * 插入多行。
   *
   * 性能说明：默认走**一次批量 INSERT**（多 VALUES 行，单条语句单往返），
   * 缺省字段用 `DEFAULT` 关键字而不是 NULL，语义与逐行插入完全一致；
   * 若引擎不支持（如个别兼容模式），自动回退为逐行插入。
   * 在高延迟链路（SSH 隧道经中继，RTT 约 1 秒）上，56 行种子的写入
   * 往返数从 ~68 降到 ~22，是「重置演示」能在连接被远端断开前完成的关键。
   *
   * @param options.returnInserted 是否读回插入后的完整对象（含子表装配），默认 true。
   *   种子/批量导入这类**不使用返回值**的场景应传 false —— 可省掉全部读回查询。
   */
  async insertMany(
    docs: Record<string, unknown>[],
    options: { returnInserted?: boolean } = {},
  ): Promise<any[]> {
    if (docs.length === 0) return [];
    const { returnInserted = true } = options;
    const client = await connectClient();
    const pk = this.spec.primaryKey ?? 'id';
    const ids: string[] = [];
    try {
      await client.query('BEGIN');

      const json = new Set(this.spec.jsonColumns ?? []);
      const skip = childKeys(this.spec);
      const flats = docs.map((doc) => (this.spec.beforeWrite ? this.spec.beforeWrite(doc) : doc));

      // 把每个文档摊平成「列名 → 值」，并收集全部出现过的列（保持首次出现顺序）
      const colOrder: string[] = [];
      const perDoc = flats.map((flat) => {
        const cells = new Map<string, unknown>();
        for (const [apiField, value] of Object.entries(flat)) {
          if (value === undefined) continue;
          if (skip.has(apiField)) continue; // 多值字段由子表承载
          const col = columnOf(this.spec, apiField);
          if (cells.has(col)) continue; // 别名重复指向同一列时只写一次
          cells.set(col, json.has(apiField) && value !== null ? JSON.stringify(value) : value);
          if (!colOrder.includes(col)) colOrder.push(col);
        }
        return cells;
      });

      /** 逐行插入（回退路径与单行场景） */
      const insertPerRow = async () => {
        for (const cells of perDoc) {
          const columns = [...cells.keys()];
          const params = [...cells.values()];
          const placeholders = params.map((_v, i) => `$${i + 1}`);
          const res = await client.query<Record<string, unknown>>(
            `INSERT INTO ${this.spec.table} (${columns.join(', ')})
             VALUES (${placeholders.join(', ')}) RETURNING ${pk}`,
            params as never[],
          );
          ids.push(String(res.rows[0][pk]));
        }
      };

      if (perDoc.length === 1) {
        await insertPerRow();
      } else {
        try {
          // 批量：每行用同一组列，缺的单元格写 DEFAULT（保留列默认值语义）
          const params: unknown[] = [];
          const rowsSql = perDoc.map((cells) => {
            const cellsSql = colOrder.map((col) => {
              if (!cells.has(col)) return 'DEFAULT';
              params.push(cells.get(col));
              return `$${params.length}`;
            });
            return `(${cellsSql.join(', ')})`;
          });
          const res = await client.query<Record<string, unknown>>(
            `INSERT INTO ${this.spec.table} (${colOrder.join(', ')})
             VALUES ${rowsSql.join(', ')} RETURNING ${pk}`,
            params as never[],
          );
          for (const row of res.rows) ids.push(String(row[pk]));
        } catch (batchErr) {
          logger.warn(
            { err: (batchErr as Error).message },
            `表 ${this.spec.table} 批量插入失败，回退为逐行插入`,
          );
          await insertPerRow();
        }
      }

      if (this.spec.persistChildren) {
        await this.spec.persistChildren(ids, docs, client);
      }
      await client.query('COMMIT');
    } catch (err) {
      try {
        await client.query('ROLLBACK');
      } catch (rollbackErr) {
        // 连接已被远端断开时 ROLLBACK 本身也会失败，不能让这个次生错误掩盖原始错误
        logger.warn({ rollbackErr }, 'openGauss 回滚失败（连接已断开）');
      }
      throw err;
    } finally {
      client.release();
    }

    if (!returnInserted) return [];

    // 批量读回：一次 SELECT + 一次批量 hydrate（不再逐行 loadOne）。
    // 按输入顺序回填，保持与逐行读回一致的返回顺序。
    const rows = await this.find({ _id: { $in: ids } });
    const byId = new Map(rows.map((r) => [String(r._id), r]));
    return ids.map((id) => byId.get(id)).filter((r): r is any => r != null);
  }

  async create(doc: Record<string, unknown>): Promise<any> {
    const [row] = await this.insertMany([doc]);
    if (!row) throw new Error(`表 ${this.spec.table} 插入后未能读回`);
    return row;
  }

  findByIdAndUpdate<T = any>(
    id: string,
    update: Record<string, unknown>,
    options: UpdateOptions = { new: true },
  ): DocQuery<T> {
    return new DocQuery<T>(this.spec, { _id: id }, async () => {
      const pk = this.spec.primaryKey ?? 'id';

      // 多值字段（子表承载）走"整体替换"：先删旧行，再插新行，与文档库的 set 语义一致
      const childUpdates = (this.spec.children ?? []).filter((c) =>
        Object.prototype.hasOwnProperty.call(update, c.key),
      );
      const scalarUpdate: Record<string, unknown> = { ...update };
      for (const c of childUpdates) delete scalarUpdate[c.key];

      const set = buildSet(this.spec, scalarUpdate, 1);
      const client = childUpdates.length > 0 ? await connectClient() : null;

      try {
        if (client) await client.query('BEGIN');

        if (set.sql) {
          const hasUpdatedAt = Object.prototype.hasOwnProperty.call(scalarUpdate, 'updatedAt');
          const touch = this.spec.columns.updatedAt && !hasUpdatedAt ? ', updated_at = now()' : '';
          const params = [...set.params, id];
          const runner = client ?? getPool();
          const res = await runner.query(
            `UPDATE ${this.spec.table} SET ${set.sql}${touch} WHERE ${pk} = $${params.length} RETURNING ${pk}`,
            params as never[],
          );
          if (res.rows.length === 0) {
            if (client) await client.query('ROLLBACK');
            return null;
          }
        } else if (childUpdates.length > 0) {
          // 仅更新子表时也要确认父记录存在
          const runner = client ?? getPool();
          const exists = await runner.query(`SELECT 1 FROM ${this.spec.table} WHERE ${pk} = $1`, [id]);
          if (exists.rows.length === 0) {
            if (client) await client.query('ROLLBACK');
            return null;
          }
        } else {
          return this.loadOne({ _id: id });
        }

        if (client) {
          for (const child of childUpdates) {
            await client.query(
              `DELETE FROM ${child.table} WHERE ${child.foreignKey} = $1`,
              [id] as never[],
            );
            await insertChildRows(client, child, id, childItemsOf(child, update));
          }
          await client.query('COMMIT');
        }
      } catch (err) {
        if (client) await client.query('ROLLBACK').catch(() => undefined);
        throw err;
      } finally {
        client?.release();
      }

      if (options.new === false) return null;
      return this.loadOne({ _id: id });
    });
  }

  async updateMany(filter: Filter, update: Record<string, unknown>): Promise<number> {
    const set = buildSet(this.spec, update, 1);
    if (!set.sql) return 0;
    const where = buildWhere(this.spec, filter);
    const params = [...set.params, ...where.params];
    const shifted = where.sql.replace(/\$(\d+)/g, (_m, n) => `$${Number(n) + set.params.length}`);
    const res = await getPool().query(
      `UPDATE ${this.spec.table} SET ${set.sql} ${shifted}`,
      params as never[],
    );
    return res.rowCount ?? 0;
  }

  async deleteMany(filter: Filter): Promise<number> {
    const where = buildWhere(this.spec, filter);
    const res = await getPool().query(
      `DELETE FROM ${this.spec.table} ${where.sql}`,
      where.params as never[],
    );
    return res.rowCount ?? 0;
  }

  /** 清空表（演示重置用）；TRUNCATE 会级联清掉子表 */
  async truncate(): Promise<void> {
    await getPool().query(`TRUNCATE TABLE ${this.spec.table} CASCADE`);
  }
}

export function createTable(spec: TableSpec): Table {
  return new Table(spec);
}
