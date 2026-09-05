import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

type BaselinePackage = {
  package: string;
  version: string;
  scope: 'tool' | 'runtime' | 'development';
};

type Baseline = {
  node: string;
  packageManager: string;
  packages: BaselinePackage[];
};

type PackageJson = {
  engines: { node: string };
  packageManager: string;
  dependencies: Record<string, string>;
  devDependencies: Record<string, string>;
};

const readJson = async <T>(path: string): Promise<T> =>
  JSON.parse(await readFile(path, 'utf8')) as T;

describe('精确依赖基线', () => {
  it('与机器基线的工具项、包集合和版本完全一致', async () => {
    const root = process.cwd();
    const baseline = await readJson<Baseline>(
      join(root, 'docs', 'baseline', 'package-baseline.json'),
    );
    const packageJson = await readJson<PackageJson>(join(root, 'package.json'));

    const runtime = Object.fromEntries(
      baseline.packages
        .filter((item) => item.scope === 'runtime')
        .map((item) => [item.package, item.version]),
    );
    const development = Object.fromEntries(
      baseline.packages
        .filter((item) => item.scope === 'development')
        .map((item) => [item.package, item.version]),
    );

    expect(packageJson.engines.node).toBe(baseline.node);
    expect(packageJson.packageManager).toBe(baseline.packageManager);
    expect(packageJson.dependencies).toEqual(runtime);
    expect(packageJson.devDependencies).toEqual(development);
  });

  it('所有项目依赖均为不含浮动标记的精确版本', async () => {
    const packageJson = await readJson<PackageJson>(join(process.cwd(), 'package.json'));
    const versions = [
      ...Object.values(packageJson.dependencies),
      ...Object.values(packageJson.devDependencies),
    ];

    expect(versions).not.toHaveLength(0);
    for (const version of versions) {
      expect(version).not.toMatch(/\^|~|latest|workspace:|\*|[<>]=?/i);
      expect(version).toMatch(/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/);
    }
  });
});
