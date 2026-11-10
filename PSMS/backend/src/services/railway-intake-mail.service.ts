import { execFile } from 'node:child_process';
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';
import { AppError } from '../middleware/errorHandler.js';
import { contentHash } from './wms-contract.js';
import { railwayImportSchema } from './railway-intake-contract.js';

// Read only the configured work-order mailbox folder. IMAP PEEK keeps messages unread.
const readAttachments = String.raw`
import email, email.policy, imaplib, json, os, ssl
e = os.environ
rows = []
def reject_constant(_):
    raise ValueError('Non-finite JSON number')
with imaplib.IMAP4_SSL(e['PSMS_RAILWAY_IMAP_HOST'], int(e.get('PSMS_RAILWAY_IMAP_PORT') or '993'),
                      ssl_context=ssl.create_default_context(), timeout=7) as mailbox:
    mailbox.login(e['PSMS_RAILWAY_IMAP_USER'], e['PSMS_RAILWAY_IMAP_PASSWORD'])
    folder = e.get('PSMS_RAILWAY_IMAP_FOLDER') or 'INBOX'
    if mailbox.select('"' + folder.replace('\\', '\\\\').replace('"', '\\"') + '"', readonly=True)[0] != 'OK':
        raise RuntimeError('Mailbox folder unavailable')
    state, ids = mailbox.uid('search', None, 'SUBJECT', '"[PSMS-RAILWAY]"')
    if state != 'OK':
        raise RuntimeError('Mailbox search failed')
    for uid in reversed(ids[0].split()[-10:]):
        state, parts = mailbox.uid('fetch', uid, '(BODY.PEEK[]<0.262145>)')
        if state != 'OK':
            raise RuntimeError('Attachment fetch failed')
        raw = next((part[1] for part in parts if isinstance(part, tuple)), b'')
        if not raw or len(raw) >= 262145:
            continue
        message = email.message_from_bytes(raw, policy=email.policy.default)
        sender = str(message.get('From', ''))[:200]
        subject = str(message.get('Subject', ''))[:200]
        if '[PSMS-RAILWAY]' not in subject:
            continue
        for part in message.iter_attachments():
            name = part.get_filename() or ''
            if not name.lower().endswith('.json'):
                continue
            payload = part.get_payload(decode=True)
            if payload and len(payload) <= 65536:
                try:
                    rows.append(dict(sender=sender, subject=subject, filename=name[:160], payload=json.loads(payload.decode('utf-8-sig'), parse_constant=reject_constant)))
                except (ValueError, UnicodeError, RecursionError):
                    rows.append(dict(sender=sender, subject=subject, filename=name[:160], payload=None))
            if len(rows) >= 10:
                break
        if len(rows) >= 10:
            break
print(json.dumps(rows, allow_nan=False))
`;

const run = promisify(execFile);
const signingKey = randomBytes(32);
let pending: ReturnType<typeof fetchAttachments> | undefined;

export function railwayMailboxConfigured() {
  return ['PSMS_RAILWAY_IMAP_HOST', 'PSMS_RAILWAY_IMAP_USER', 'PSMS_RAILWAY_IMAP_PASSWORD']
    .every(key => Boolean(process.env[key]));
}

async function fetchAttachments() {
  const keys = ['HOST', 'PORT', 'USER', 'PASSWORD', 'FOLDER'].map(key => 'PSMS_RAILWAY_IMAP_' + key);
  const env = Object.fromEntries(keys.map(key => [key, process.env[key]]));
  try {
    const { stdout } = await run('python3', ['-I', '-c', readAttachments], { timeout: 15000, maxBuffer: 2 * 1024 * 1024,
      env: { PATH: process.env.PATH, SYSTEMROOT: process.env.SYSTEMROOT, ...env } });
    return JSON.parse(stdout) as { sender: string; subject: string; filename: string; payload: unknown }[];
  } catch {
    throw new AppError(502, 'RAILWAY_IMAP_ERROR', '读取工单邮箱失败，请管理员检查服务器收件配置与连接');
  }
}

function sign(encoded: string) {
  return createHmac('sha256', signingKey).update(encoded).digest();
}

export async function railwayMailCandidates(subject: string) {
  if (!railwayMailboxConfigured()) throw new AppError(503, 'RAILWAY_MAIL_UNCONFIGURED', '服务器尚未配置工单收件邮箱');
  pending ??= fetchAttachments().finally(() => { pending = undefined; });
  return (await pending).map(({ sender, subject: mailSubject, filename, payload }) => {
    const parsed = railwayImportSchema.safeParse(payload);
    if (!parsed.success) return { sender, subject: mailSubject, filename, error: '附件格式有误，请按 WMS 工单模板重发' };
    const encoded = Buffer.from(JSON.stringify({ subject, sender, filename, digest: contentHash(parsed.data),
      expiry: Date.now() + 15 * 60 * 1000 })).toString('base64url');
    return { sender, subject: mailSubject, filename, payload: parsed.data, proof: encoded + '.' + sign(encoded).toString('base64url') };
  });
}

export function verifyRailwayMail(proof: string, payload: unknown, subject: string) {
  try {
    const [encoded, signature, extra] = proof.split('.');
    const expected = sign(encoded);
    const actual = Buffer.from(signature, 'base64url');
    if (extra !== undefined || actual.length !== expected.length || !timingSafeEqual(actual, expected)) throw new Error();
    const data = JSON.parse(Buffer.from(encoded, 'base64url').toString('utf8'));
    if (data.subject !== subject || data.expiry <= Date.now() || data.digest !== contentHash(payload)) throw new Error();
    return { filename: String(data.filename), sender: String(data.sender) };
  } catch {
    throw new AppError(400, 'RAILWAY_MAIL_PROOF_INVALID', '邮件附件核对已过期或内容已变化，请重新读取邮箱');
  }
}