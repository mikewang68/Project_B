export const ACTIONS = Object.freeze([
  'VIEW', 'ADD', 'EDIT', 'DELETE', 'UPLOAD', 'DOWNLOAD', 'FAULT_ALARM',
  'RECEIVE_EMAIL', 'EXPORT', 'CONFIRM', 'AUDIT_VERIFY', 'CHAIN_SUBMIT',
] as const);
/** Initial dictionary seeds; the trusted IAM dictionary may register more actions. */
export type BuiltinAction = typeof ACTIONS[number];
export type Action = BuiltinAction | (string & {});
export type Mode = 'GLOBAL' | 'SCOPED' | 'DENY';
export type ColumnProfile = 'STANDARD' | 'ORDER';
export type Triple = Readonly<{ projectId: string; regionId: string; subsystemCode: string }>;
export type Filters = Readonly<{
  my_info: string; my_project_zone_info: string;
  my_info_template: string; my_project_zone_info_template: string;
  scope_params: Readonly<Record<string, string>>;
}>;
export type UserContext = Readonly<Filters & {
  contextVersion: 2; userId: string; username: string; userCode: string; displayName: string;
  adminLevel: 'L1_SUPER' | 'L1_READONLY' | 'L2_PROJECT' | 'L3_SUBSYSTEM' | 'L4_OPERATOR';
  readOnly: boolean; serviceAudience: string; projectId: string | null;
  regionId: string | null; subsystemCode: string | null; scopeMode: Mode;
  allowedScopes: readonly Triple[]; activeScope: readonly Triple[]; roles: readonly string[];
  visibleMenuKeys: readonly string[]; menuOperations: Readonly<Record<string, readonly Action[]>>;
  managementCapabilities: readonly string[]; authorizationVersion: string;
}>;
export type Binding = Readonly<{ subject: string; sessionId: string; localSubject: string }>;
export type Authorization = Readonly<{
  binding: Binding; context: UserContext; permissions: readonly string[];
}>;
export type AuthorizedFilter = Readonly<{
  condition: string; template: string; parameters: Readonly<Record<string, string>>;
}>;
const READONLY = new Set<Action>(['VIEW', 'DOWNLOAD', 'EXPORT', 'AUDIT_VERIFY']);
const LEVELS = new Set(['L1_SUPER', 'L1_READONLY', 'L2_PROJECT', 'L3_SUBSYSTEM', 'L4_OPERATOR']);
const PROFILES = Object.freeze({
  STANDARD: ['project_id', 'region_id', 'subsystem_code'],
  ORDER: ['o.project_id', 'o.region_id', 'o.subsystem_code'],
} as const);

export class ContextError extends Error {
  constructor(public readonly code: string, public readonly status = 403) { super(code); }
}
function fail(code = 'INVALID_TRUSTED_CONTEXT'): never { throw new ContextError(code); }
function record(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return fail();
  return value as Record<string, unknown>;
}
function text(value: unknown): string {
  if (typeof value !== 'string' || !value.trim()) return fail();
  return value;
}
function strings(value: unknown): readonly string[] {
  if (!Array.isArray(value) || value.some(v => typeof v !== 'string' || !v.trim())) return fail();
  return Object.freeze([...new Set(value as string[])]);
}
function validAction(value: unknown): value is Action {
  return typeof value === 'string' && value === value.trim()
    && /^[A-Z][A-Z0-9_]{0,63}$/.test(value);
}
export function identifier(value: unknown, max = 64): string {
  if (typeof value !== 'string' || value.length < 1 || value.length > max
      || !/^[\x41-\x5A\x61-\x7A\x30-\x39_-]+$/.test(value)) return fail('INVALID_SCOPE_IDENTIFIER');
  return value;
}
function triple(value: unknown): Triple {
  const v = record(value);
  return Object.freeze({ projectId: identifier(v.projectId), regionId: identifier(v.regionId),
    subsystemCode: identifier(v.subsystemCode, 32) });
}
function key(t: Triple): string { return `${t.projectId}\0${t.regionId}\0${t.subsystemCode}`; }
function compare(a: string, b: string): number { return a < b ? -1 : a === b ? 0 : 1; }
function tuples(value: unknown): readonly Triple[] {
  if (!Array.isArray(value) || value.length > 512) return fail('INVALID_SCOPE_TUPLES');
  return Object.freeze(value.map(triple));
}
function columns(profile: ColumnProfile): readonly string[] {
  if (!Object.hasOwn(PROFILES, profile)) return fail('UNREGISTERED_COLUMN_PROFILE');
  return PROFILES[profile];
}
function join(terms: readonly string[]): string {
  return terms.length === 1 ? ` AND ${terms[0]} ` : ` AND ((${terms.join(') OR (')})) `;
}
export function buildFilters(mode: Mode, scopes: readonly Triple[], profile: ColumnProfile = 'STANDARD'): Filters {
  const [p, r, s] = columns(profile);
  if (!['GLOBAL', 'SCOPED', 'DENY'].includes(mode)) return fail('INVALID_CONTEXT_MODE');
  const constant = (v: string): Filters => Object.freeze({
    my_info: v, my_project_zone_info: v, my_info_template: v,
    my_project_zone_info_template: v, scope_params: Object.freeze({}),
  });
  if (mode === 'GLOBAL') return constant(' AND 1=1 ');
  const unique = new Map(scopes.map(v => { const t = triple(v); return [key(t), t]; }));
  if (mode === 'DENY' || !unique.size) return constant(' AND 1=0 ');
  if (unique.size > 512) return fail('TOO_MANY_SCOPE_TUPLES');
  const sorted = [...unique.values()].sort((a, b) => compare(key(a), key(b)));
  const raw: string[] = [], shared: string[] = [], template: string[] = [], sharedTemplate: string[] = [];
  const params: Record<string, string> = {}, pairs = new Set<string>();
  sorted.forEach((t, i) => {
    raw.push(`${p} = '${t.projectId}' AND ${r} = '${t.regionId}' AND ${s} = '${t.subsystemCode}'`);
    template.push(`${p} = :p${i} AND ${r} = :r${i} AND ${s} = :s${i}`);
    params[`p${i}`] = t.projectId; params[`r${i}`] = t.regionId; params[`s${i}`] = t.subsystemCode;
    const pair = `${t.projectId}\0${t.regionId}`;
    if (!pairs.has(pair)) {
      pairs.add(pair);
      shared.push(`${p} = '${t.projectId}' AND ${r} = '${t.regionId}'`);
      sharedTemplate.push(`${p} = :p${i} AND ${r} = :r${i}`);
    }
  });
  return Object.freeze({ my_info: join(raw), my_project_zone_info: join(shared),
    my_info_template: join(template), my_project_zone_info_template: join(sharedTemplate),
    scope_params: Object.freeze(params) });
}
function sameMap(actual: unknown, expected: Readonly<Record<string, string>>): boolean {
  const a = record(actual), keys = Object.keys(a);
  return keys.length === Object.keys(expected).length
    && keys.every(k => Object.hasOwn(expected, k) && a[k] === expected[k]);
}
/** Call only on the body returned through the authenticated IAM service transport. */
export function validateContext(value: unknown, subject: string, audience: string): UserContext {
  const c = record(value), aud = identifier(audience, 32).toUpperCase();
  if (c.contextVersion !== 2 || c.userId !== text(subject) || c.serviceAudience !== aud
      || typeof c.readOnly !== 'boolean' || !LEVELS.has(String(c.adminLevel))) return fail();
  const level = c.adminLevel as UserContext['adminLevel'];
  const allowed = tuples(c.allowedScopes), active = tuples(c.activeScope);
  const allowedKeys = new Set(allowed.map(key));
  if (active.some(t => !allowedKeys.has(key(t)))) return fail('ACTIVE_SCOPE_NOT_AUTHORIZED');
  const l1 = level === 'L1_SUPER' || level === 'L1_READONLY';
  const mode = c.scopeMode as Mode;
  if (!['GLOBAL', 'SCOPED', 'DENY'].includes(mode) || mode === 'GLOBAL' && !l1
      || mode === 'SCOPED' && !active.length || level === 'L1_READONLY' && !c.readOnly) return fail('INVALID_CONTEXT_MODE');
  if (!l1) {
    const project = identifier(c.projectId);
    if (allowed.some(t => t.projectId !== project)) return fail('CROSS_PROJECT_CONTEXT');
  }
  if (level === 'L3_SUBSYSTEM' || level === 'L4_OPERATOR') {
    const fixed = triple(c);
    if (allowed.some(t => key(t) !== key(fixed))) return fail('UNLOCKED_ACCOUNT_CONTEXT');
  }
  if (mode === 'SCOPED' && aud !== 'IAM' && active.some(t => t.subsystemCode !== aud)) return fail('CROSS_SYSTEM_CONTEXT');
  const filters = buildFilters(mode, active);
  for (const field of ['my_info', 'my_project_zone_info', 'my_info_template', 'my_project_zone_info_template'] as const)
    if (c[field] !== filters[field]) return fail('CONTEXT_FILTER_MISMATCH');
  if (!sameMap(c.scope_params, filters.scope_params)) return fail('CONTEXT_FILTER_MISMATCH');
  const operations: Record<string, readonly Action[]> = {};
  for (const [menu, value] of Object.entries(record(c.menuOperations))) {
    const actions = strings(value);
    if (actions.some(a => !validAction(a))) return fail('UNKNOWN_OPERATION');
    Object.defineProperty(operations, menu, { enumerable: true, value: actions });
  }
  const nullable = (v: unknown): string | null => v === null ? null : text(v);
  return Object.freeze({ ...filters, contextVersion: 2, userId: text(c.userId), username: text(c.username),
    userCode: text(c.userCode), displayName: text(c.displayName), adminLevel: level, readOnly: c.readOnly,
    serviceAudience: aud, projectId: nullable(c.projectId), regionId: nullable(c.regionId),
    subsystemCode: nullable(c.subsystemCode), scopeMode: mode, allowedScopes: allowed, activeScope: active,
    roles: strings(c.roles), visibleMenuKeys: strings(c.visibleMenuKeys), menuOperations: Object.freeze(operations),
    managementCapabilities: strings(c.managementCapabilities), authorizationVersion: text(c.authorizationVersion) });
}
export function requireAction(context: UserContext, menu: string, action: Action): void {
  if (!validAction(action) || context.scopeMode === 'DENY'
      || context.readOnly && !READONLY.has(action)
      || !Object.hasOwn(context.menuOperations, menu)
      || !context.menuOperations[menu]?.includes(action)) return fail('OPERATION_DENIED');
}
/** Convert only a registered, server-authorized template; never accept browser SQL. */
export function postgresFilter(filter: AuthorizedFilter, profile: ColumnProfile = 'STANDARD'): Readonly<{
  text: string; values: readonly string[];
}> {
  const allowedColumns = columns(profile), parameters = record(filter.parameters);
  if (Object.keys(parameters).length > 1536 || typeof filter.template !== 'string'
      || filter.template.length > 160000 || !filter.template.startsWith(' AND ')) return fail('INVALID_RESOURCE_FILTER');
  for (const [k, v] of Object.entries(parameters)) {
    if (!/^[prs](0|[1-9][0-9]{0,2})$/.test(k) || Number(k.slice(1)) > 511) return fail('INVALID_FILTER_PARAMETER');
    identifier(v, k[0] === 's' ? 32 : 64);
  }
  const pattern = /\s+|AND\b|OR\b|[()]|=|1\b|0\b|:[prs](?:0|[1-9][0-9]{0,2})\b|(?:o\.)?(?:project_id|region_id|subsystem_code)\b/g;
  const tokens: string[] = [];
  let end = 0;
  for (const match of filter.template.matchAll(pattern)) {
    if (match.index !== end) return fail('INVALID_FILTER_SYNTAX');
    end += match[0].length;
    if (match[0].trim()) tokens.push(match[0]);
  }
  if (end !== filter.template.length || tokens.shift() !== 'AND') return fail('INVALID_FILTER_SYNTAX');
  let i = 0;
  const atom = (depth: number): void => {
    if (depth > 8) return fail('INVALID_FILTER_SYNTAX');
    if (tokens[i] === '(') {
      i++; expression(depth + 1);
      if (tokens[i++] !== ')') return fail('INVALID_FILTER_SYNTAX');
      return;
    }
    const left = tokens[i++];
    if (tokens[i++] !== '=') return fail('INVALID_FILTER_SYNTAX');
    const right = tokens[i++];
    if (left === '1' && (right === '0' || right === '1')) return;
    if (!left || !allowedColumns.includes(left) || !right?.startsWith(':')
        || !Object.hasOwn(parameters, right.slice(1))) return fail('INVALID_FILTER_SYNTAX');
    const prefix = left.endsWith('project_id') ? 'p' : left.endsWith('region_id') ? 'r' : 's';
    if (right[1] !== prefix) return fail('INVALID_FILTER_PARAMETER');
  };
  const expression = (depth: number): void => {
    atom(depth);
    while (tokens[i] === 'AND' || tokens[i] === 'OR') { i++; atom(depth); }
  };
  expression(0);
  if (i !== tokens.length) return fail('INVALID_FILTER_SYNTAX');
  const used = new Map<string, number>(), values: string[] = [];
  const raw = filter.template.replace(/:([prs][0-9]+)/g, (_, k: string) => `'${parameters[k]}'`);
  if (raw !== filter.condition) return fail('FILTER_LITERAL_MISMATCH');
  const sql = filter.template.replace(/:([prs][0-9]+)/g, (_, k: string) => {
    if (!used.has(k)) { values.push(String(parameters[k])); used.set(k, values.length); }
    return '$' + used.get(k);
  });
  return Object.freeze({ text: sql, values: Object.freeze(values) });
}

export class IamContextClient {
  #base: string;
  #audience: string;
  #credential: string;
  #timeout: number;
  constructor(options: { baseUrl: string; audience: string; serviceToken: string;
    allowLoopbackHttp?: boolean; timeoutMs?: number }) {
    const url = new URL(options.baseUrl);
    const loopback = ['127.0.0.1', '[::1]', 'localhost'].includes(url.hostname);
    if (url.username || url.password || url.search || url.hash || url.protocol !== 'https:'
        && !(options.allowLoopbackHttp && url.protocol === 'http:' && loopback)) throw new ContextError('INSECURE_IAM_URL');
    this.#base = url.href.replace(/\/$/, '');
    this.#audience = identifier(options.audience, 32).toLowerCase();
    if (typeof options.serviceToken !== 'string' || options.serviceToken.length < 32) throw new ContextError('INVALID_SERVICE_CREDENTIAL');
    this.#credential = options.serviceToken;
    this.#timeout = options.timeoutMs ?? 5000;
    if (!Number.isInteger(this.#timeout) || this.#timeout < 100 || this.#timeout > 30000) throw new ContextError('INVALID_TIMEOUT');
  }
  async #call(path: string, body: Record<string, unknown>): Promise<Record<string, unknown>> {
    try {
      const response = await fetch(this.#base + '/auth/sso/' + path, {
        method: 'POST', redirect: 'error', signal: AbortSignal.timeout(this.#timeout),
        headers: { 'Content-Type': 'application/json', 'X-SSO-Service-Token': this.#credential },
        body: JSON.stringify({ ...body, audience: this.#audience }),
      });
      if (response.status === 401 || response.status === 403) throw new ContextError('CENTRAL_AUTHORIZATION_DENIED', response.status);
      if (!response.ok) throw new ContextError('IAM_UNAVAILABLE', 503);
      const envelope = record(await response.json());
      if (envelope.code !== 0) throw new ContextError('IAM_INVALID_RESPONSE', 503);
      return record(envelope.data);
    } catch (error) {
      if (error instanceof ContextError) throw error;
      throw new ContextError('IAM_UNAVAILABLE', 503);
    }
  }
  #resolve(body: Record<string, unknown>, previous?: Binding): Authorization {
    const binding = Object.freeze({ subject: text(body.subject), sessionId: text(body.sessionId),
      localSubject: text(body.localSubject) });
    const context = validateContext(body.userContext, binding.subject, this.#audience);
    if (body.active !== true || body.contractVersion !== '1.3' || body.audience !== this.#audience
        || body.username !== context.username || body.readOnly !== context.readOnly || context.scopeMode === 'DENY'
        || previous && Object.keys(binding).some(k => binding[k as keyof Binding] !== previous[k as keyof Binding])) return fail('INVALID_CENTRAL_ASSERTION');
    return Object.freeze({ binding, context, permissions: strings(body.permissions) });
  }
  async consume(ticket: string): Promise<Authorization> {
    if (typeof ticket !== 'string' || !ticket || ticket.length > 8192) return fail('INVALID_TICKET');
    return this.#resolve(await this.#call('verify-and-consume', { ticket }));
  }
  async current(binding: Binding): Promise<Authorization> {
    const subject = text(binding.subject), sessionId = text(binding.sessionId);
    text(binding.localSubject);
    return this.#resolve(await this.#call('session/introspect', { subject, sessionId }), binding);
  }
  async resourceFilter(binding: Pick<Binding, 'subject' | 'sessionId'>, request: {
    resourceKey: string; menuKey: string; action: Action; profile?: ColumnProfile;
  }): Promise<AuthorizedFilter> {
    if (!validAction(request.action)) return fail('UNKNOWN_OPERATION');
    const body = await this.#call('resource-filter', { subject: text(binding.subject),
      sessionId: text(binding.sessionId), resourceKey: text(request.resourceKey),
      menuKey: text(request.menuKey), action: request.action });
    if (body.resourceKey !== request.resourceKey || body.action !== request.action) return fail('RESOURCE_RESPONSE_MISMATCH');
    const f = record(body.filter), parameters = { ...record(f.parameters) } as Record<string, string>;
    const filter = Object.freeze({ condition: text(f.condition), template: text(f.template),
      parameters: Object.freeze(parameters) });
    postgresFilter(filter, request.profile);
    return filter;
  }
}
