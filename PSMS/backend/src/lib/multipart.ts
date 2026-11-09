/**
 * 最小 multipart/form-data 解析器。
 *
 * 只用于计划文件导入：读取第一个带文件名的 part 的字节内容。
 * 不引入 multer/busboy —— 生产镜像里的依赖是冻结的，新增原生依赖会扩大升级面。
 * 限制由调用方通过 express.raw({ limit }) 保证（2 MB）。
 */
export interface MultipartFile {
  field: string;
  filename: string;
  contentType: string;
  data: Buffer;
}

export function parseMultipartFile(body: Buffer, contentType: string): MultipartFile {
  const boundary = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType);
  const marker = boundary?.[1] ?? boundary?.[2]?.trim();
  if (!marker) throw new Error('multipart boundary 缺失');
  const delimiter = Buffer.from(`--${marker}`);
  const headerSeparator = Buffer.from('\r\n\r\n');
  let cursor = body.indexOf(delimiter);
  while (cursor >= 0) {
    cursor += delimiter.length;
    if (body.subarray(cursor, cursor + 2).toString() === '--') break;
    if (body.subarray(cursor, cursor + 2).toString() === '\r\n') cursor += 2;
    const headerEnd = body.indexOf(headerSeparator, cursor);
    if (headerEnd < 0) break;
    const headerText = body.subarray(cursor, headerEnd).toString('utf8');
    const next = body.indexOf(delimiter, headerEnd + headerSeparator.length);
    if (next < 0) break;
    // 去掉 part 内容末尾的 CRLF
    let end = next;
    if (end >= 2 && body.subarray(end - 2, end).toString() === '\r\n') end -= 2;
    const disposition = /content-disposition:([^\r\n]+)/i.exec(headerText)?.[1] ?? '';
    const field = /name="([^"]*)"/i.exec(disposition)?.[1] ?? '';
    const filename = /filename="([^"]*)"/i.exec(disposition)?.[1] ?? '';
    const partType = /content-type:\s*([^\r\n]+)/i.exec(headerText)?.[1]?.trim() ?? 'application/octet-stream';
    if (filename) return { field, filename, contentType: partType, data: body.subarray(headerEnd + headerSeparator.length, end) };
    cursor = next;
  }
  throw new Error('multipart 中没有文件字段');
}
