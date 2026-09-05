import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { describe, expect, it } from 'vitest';

const sourceExtensions = /\.(?:css|html|ts|tsx)$/i;
const excludedDirectories = new Set(['__tests__', 'test']);

const collectFiles = (directory: string): string[] => {
  if (!existsSync(directory)) return [];

  return readdirSync(directory).flatMap((entry) => {
    const path = join(directory, entry);
    if (statSync(path).isDirectory()) {
      return excludedDirectories.has(entry) ? [] : collectFiles(path);
    }
    return sourceExtensions.test(entry) ? [path] : [];
  });
};

describe('离线资源约束', () => {
  it('源码和 index.html 不引用公网资源、生产接口或公网地图', () => {
    const root = process.cwd();
    const indexPath = join(root, 'index.html');
    const files = [
      ...collectFiles(join(root, 'src')),
      ...(existsSync(indexPath) ? [indexPath] : []),
    ];
    const forbidden = [
      /https?:\/\//i,
      /(?:src|href)\s*=\s*["']\/\//i,
      /fonts\.(?:googleapis|gstatic)\.com/i,
      /(?:cdn|unpkg|jsdelivr|cdnjs)\./i,
      /(?:api|wms|ecs)\.[0-9a-z.-]+\.[a-z]{2,}/i,
      /(?:tile|tiles)\.(?:openstreetmap|mapbox)\./i,
    ];

    for (const file of files) {
      const content = readFileSync(file, 'utf8');
      for (const pattern of forbidden) {
        expect(content, `${relative(root, file)} 命中 ${pattern}`).not.toMatch(pattern);
      }
    }
  });
});
