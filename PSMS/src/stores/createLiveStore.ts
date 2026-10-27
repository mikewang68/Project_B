import { createStore } from 'zustand/vanilla';
import { domainSchemas, type DomainObjectId } from '../contracts';
import type { DemoRootState, DemoStoreApi, DomainStateMutator } from './types';

const locations: Array<[DomainObjectId, keyof DemoRootState, string]> = [
  ['DO-001','plan','plans'],['DO-002','plan','waybills'],['DO-003','resource','tracks'],
  ['DO-004','resource','materials'],['DO-005','workOrder','workOrders'],['DO-006','workOrder','nodes'],
  ['DO-007','resource','resources'],['DO-008','vehicle','appointments'],['DO-009','exception','exceptions'],
  ['DO-010','interlock','interlocks'],['DO-011','offline','packets'],['DO-012','report','reports'],
  ['DO-013','configAudit','audit'],['DO-014','configAudit','userRoles'],['DO-015','systemConfig','configVersions'],
];
export function createLiveStore(initial: DemoRootState): DemoStoreApi {
  const validate = (value: DemoRootState) => {
    for (const [id,slice,key] of locations) {
      const state = value[slice] as unknown as Record<string, unknown>;
      domainSchemas[id].array().parse(state[key]);
    }
    return value;
  };
  const store = createStore<DemoRootState>()(() => validate(structuredClone(initial)));
  const replaceDomainState = (mutator: DomainStateMutator) => {
    const next = structuredClone(store.getState());mutator(next);store.setState(validate(next), true);
  };
  return Object.assign(store, { replaceDomainState,
    resetFromSnapshot: () => { throw new Error('业务接口模式禁止演示数据重置'); } });
}
