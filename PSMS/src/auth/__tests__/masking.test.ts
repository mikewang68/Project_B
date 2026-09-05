import { describe, expect, it } from 'vitest';

import { maskMobile, maskName, maskSensitiveFields, maskVehicleNo } from '..';

describe('C03 deterministic masking', () => {
  it('masks a name in the frozen format', () => {
    expect(maskName('张三')).toBe('张**');
  });

  it('masks a mobile number in the frozen format', () => {
    expect(maskMobile('13800121234')).toBe('138****1234');
  });

  it('masks a vehicle number in the frozen format', () => {
    expect(maskVehicleNo('川A·12345')).toBe('川A·***45');
  });

  it('returns a new masked view without mutating the source record', () => {
    const source = {
      id: 'PERSON-001',
      name: '张三',
      mobile: '13800121234',
      vehicleNo: '川A·12345',
    };
    const before = structuredClone(source);

    const masked = maskSensitiveFields(source);

    expect(masked).not.toBe(source);
    expect(masked).toEqual({
      id: 'PERSON-001',
      name: '张**',
      mobile: '138****1234',
      vehicleNo: '川A·***45',
    });
    expect(source).toEqual(before);
  });
});
