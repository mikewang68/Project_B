export function maskName(value: string): string {
  return value.length === 0 ? value : `${Array.from(value)[0]}**`;
}

export function maskMobile(value: string): string {
  return value.replace(/^(\d{3})\d{4}(\d{4})$/, '$1****$2');
}

export function maskVehicleNo(value: string): string {
  return value.replace(/^(.+·).{3}(.{2})$/, '$1***$2');
}

export function maskSensitiveFields<
  T extends { name?: string; mobile?: string; vehicleNo?: string },
>(source: T): T {
  return {
    ...source,
    ...(typeof source.name === 'string' ? { name: maskName(source.name) } : {}),
    ...(typeof source.mobile === 'string' ? { mobile: maskMobile(source.mobile) } : {}),
    ...(typeof source.vehicleNo === 'string' ? { vehicleNo: maskVehicleNo(source.vehicleNo) } : {}),
  };
}
