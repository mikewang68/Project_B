import { do001Schema, type Plan } from '../../contracts';
export const IMPORT_STORAGE_KEY = 'production-dispatch:imported-plans:v1';
let imported: Plan[] = [];
export function loadImportedPlans(): Plan[] {
  if (typeof localStorage !== 'undefined') {
    const raw = localStorage.getItem(IMPORT_STORAGE_KEY);
    try { imported = raw ? do001Schema.array().parse(JSON.parse(raw)) : []; }
    catch { imported = []; console.warn('PSMS 本地导入数据格式异常，原始保存内容仍保留。'); }
  }
  return structuredClone(imported);
}
export function getImportedPlans(): Plan[] { return structuredClone(imported); }
export function saveImportedPlans(plans: readonly Plan[]) {
  const validated = do001Schema.array().parse(plans);
  if (typeof localStorage !== 'undefined') localStorage.setItem(IMPORT_STORAGE_KEY, JSON.stringify(validated));
  imported = validated;
}
