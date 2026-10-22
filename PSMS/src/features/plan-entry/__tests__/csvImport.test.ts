import { describe, expect, it } from 'vitest';
import { csvTemplate, parsePlanCsv, readCsv } from '../csvImport';
import { chooseDefaultPlan } from '../components/PlanPicker';

describe('手工计划 CSV 导入', () => {
  it('支持 BOM、中文表头和待确认计划，不自动确认外部数据', () => {
    expect(parsePlanCsv(csvTemplate)[0]).toMatchObject({ planBatchNo: 'IMPORT-20261012-01', status: 'PENDING_CONFIRM', sourceSystem: 'MANUAL_CSV' });
  });
  it('支持带引号的逗号、换行和双引号', () => {
    expect(readCsv('a,b\r\n"x,y","z""k\nl"')).toEqual([['a', 'b'], ['x,y', 'z"k\nl']]);
    expect(() => readCsv('a\n"x')).toThrow('引号未闭合');
    expect(() => readCsv('a\n"x"y')).toThrow('额外字符');
  });
  it('拒绝文件内重复批次和已有批次', () => {
    const row = csvTemplate.split('\r\n')[1];
    expect(() => parsePlanCsv(csvTemplate + row)).toThrow('计划批次重复');
    expect(() => parsePlanCsv(csvTemplate, parsePlanCsv(csvTemplate))).toThrow('计划批次重复');
  });
  it('拒绝缺列、错误货类、不存在日期和逆序时间', () => {
    expect(() => parsePlanCsv('车次\nX')).toThrow('缺少列');
    expect(() => parsePlanCsv(csvTemplate.replace('FLY_ASH', 'UNKNOWN'))).toThrow('货类');
    expect(() => parsePlanCsv(csvTemplate.replaceAll('2026-10-12', '2026-02-30'))).toThrow('时间');
    expect(() => parsePlanCsv(csvTemplate.replace('T10:00:00', 'T07:00:00'))).toThrow('晚于');
  });
  it('默认选第一个已确认且未拆解计划，避免跳转到虚构 ID', () => {
    const plans = [{ id: 'old', status: 'DECOMPOSED' }, { id: 'pending', status: 'PENDING_CONFIRM' }, { id: 'ready', status: 'CONFIRMED' }];
    expect(chooseDefaultPlan(plans, 'tasks')?.id).toBe('ready');
    expect(chooseDefaultPlan([], 'tasks')).toBeUndefined();
  });
});
