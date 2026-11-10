import { createHash } from 'node:crypto';
import { z } from 'zod';
import { quantityMillis, workOrderDispatchDetails } from './wms-contract.js';

const text = z.string().trim().min(1).max(160);
export const railwayDetailsSchema = z.object({
  scenarioCode: text.optional(), scenarioName: text.optional(), workSummary: z.string().min(1).max(2000).optional(),
  companyCode: text, companyName: text, ownerCode: text, ownerName: text,
  destinationStation: text, cargoName: text, cargoSpecification: text,
  priority: z.enum(['HIGH', 'MEDIUM', 'LOW']),
  dispatch: workOrderDispatchDetails,
  wagons: z.array(z.object({ wagonNo: text, wagonType: text, plannedWeight: z.number().positive().max(100), unit: z.literal('t') }).strict()).min(1).max(100),
  resources: z.object({ equipmentId: text, equipmentName: text, crewCode: text, crewName: text,
    operatorCode: text, operatorName: text }).strict(),
  checkpoints: z.array(text).min(1).max(20),
  materialDescriptions: z.array(z.object({ lineId: text, goodName: text, specification: text,
    packaging: text, supplierName: text }).strict()).max(100).optional(),
}).strict();

export const railwayTicketSchema = z.object({
  version: z.literal('psms.railway-order.v1'), simulated: z.literal(true),
  id: z.string().regex(/^SIM-RAIL-\d{8}$/), date: z.iso.date(),
  trainNo: z.string().regex(/^SIM-\d{5}$/),
  cargoType: z.enum(['COAL', 'IRON_ORE', 'CONTAINER', 'CEMENT', 'FLY_ASH', 'STEEL']),
  weight: z.number().int().min(100).max(5000), workArea: z.literal('AREA-A'),
  trackNo: z.enum(['T1', 'T2', 'T3', 'T4']), sourceStation: z.literal('模拟铁路编组站'),
  arrivalTime: z.iso.datetime({ offset: true }), departureTime: z.iso.datetime({ offset: true }),
  details: railwayDetailsSchema.optional(),
}).strict().superRefine((ticket, ctx) => {
  if (ticket.id !== `SIM-RAIL-${ticket.date.replaceAll('-', '')}` || Date.parse(ticket.departureTime) <= Date.parse(ticket.arrivalTime))
    ctx.addIssue({ code: 'custom', message: '工单日期、编号或到发时间不一致' });
  if (!ticket.details) return;
  const details = ticket.details;
  if (details.dispatch.expectedArrivalAt !== ticket.arrivalTime
      || details.resources.equipmentId !== ticket.id + '-EQ')
    ctx.addIssue({ code: 'custom', message: '作业安排与铁路工单关联不一致' });
  if (details.dispatch.lines.some(line => line.unit !== 't')
      || details.dispatch.lines.reduce((sum, line) => sum + quantityMillis(line.plannedQty), 0) !== quantityMillis(ticket.weight)
      || details.wagons.reduce((sum, wagon) => sum + quantityMillis(wagon.plannedWeight), 0) !== quantityMillis(ticket.weight))
    ctx.addIssue({ code: 'custom', message: '物料明细、车皮明细与计划总重量不一致' });
  if (new Set(details.wagons.map(wagon => wagon.wagonNo)).size !== details.wagons.length)
    ctx.addIssue({ code: 'custom', message: '车皮编号必须唯一' });
  if (details.materialDescriptions && (details.materialDescriptions.length !== details.dispatch.lines.length
      || new Set(details.materialDescriptions.map(line => line.lineId)).size !== details.materialDescriptions.length
      || details.materialDescriptions.some(line => !details.dispatch.lines.some(original => original.lineId === line.lineId))))
    ctx.addIssue({ code: 'custom', message: '物料说明必须逐行对应派遣物料' });
});
export type RailwayTicket = z.infer<typeof railwayTicketSchema>;

export function shanghaiDate(now = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
}

const cargoProfiles = [
  { type: 'COAL', name: '煤炭', specification: '模拟散装煤', wagonType: '模拟敞车', area: '煤炭接卸点',
    owners: ['模拟临港能源', '模拟北区供热', '模拟海港燃料'], materials: ['模拟动力煤', '模拟混配煤'], packaging: '散装，按吨计量' },
  { type: 'IRON_ORE', name: '铁矿石', specification: '模拟散装矿石', wagonType: '模拟敞车', area: '矿石接卸点',
    owners: ['模拟港城钢铁', '模拟临港冶金', '模拟东区矿业'], materials: ['模拟块矿', '模拟矿粉'], packaging: '散装，分批记录' },
  { type: 'CONTAINER', name: '集装箱货物', specification: '模拟箱装货物，按计划重量记录', wagonType: '模拟集装箱平车', area: '集装箱卸车点',
    owners: ['模拟海联物流', '模拟港城贸易', '模拟联运供应链'], materials: ['模拟箱装机电货物', '模拟箱装日用货物', '模拟箱装包装材料'], packaging: '箱装，按计划重量记录' },
  { type: 'CEMENT', name: '水泥', specification: '模拟水泥到货', wagonType: '模拟散装货物车', area: '水泥接卸点',
    owners: ['模拟临港建材', '模拟城建供应链', '模拟东区商混'], materials: ['模拟散装水泥', '模拟袋装水泥'], packaging: '按批次、计划重量记录' },
  { type: 'FLY_ASH', name: '粉煤灰', specification: '模拟散装粉煤灰', wagonType: '模拟散装货物车', area: '粉煤灰接卸点',
    owners: ['模拟绿色建材', '模拟临港新材料', '模拟循环资源'], materials: ['模拟粉煤灰A批', '模拟粉煤灰B批'], packaging: '散装，按吨计量' },
  { type: 'STEEL', name: '钢材', specification: '模拟钢材到货，按计划重量记录', wagonType: '模拟平车', area: '钢材卸车点',
    owners: ['模拟港城钢贸', '模拟重工材料', '模拟临港金属'], materials: ['模拟钢板', '模拟型钢', '模拟钢卷'], packaging: '成捆或成卷，按计划重量记录' },
] as const;

export function generateRailwayDetails(ticket: Omit<RailwayTicket, 'details'>): z.infer<typeof railwayDetailsSchema> {
  const profile = cargoProfiles.find(item => item.type === ticket.cargoType)!;
  const seed = createHash('sha256').update(ticket.id + ticket.trainNo).digest().readUInt32BE();
  const compactDate = ticket.date.replaceAll('-', ''), resourceNo = String(1 + seed % 9).padStart(2, '0');
  const ownerName = profile.owners[(seed >>> 4) % profile.owners.length];
  const materialCount = 1 + (seed >>> 8) % profile.materials.length;
  const shares = materialCount === 1 ? [100] : materialCount === 2 ? [40 + seed % 3 * 10, 60 - seed % 3 * 10] : [40, 35, 25];
  const shuttle = profile.type === 'CONTAINER' || profile.type === 'STEEL' || seed % 2 === 1;
  const wagonLoad = profile.type === 'CONTAINER' ? 50 : profile.type === 'STEEL' ? 55 : 60;
  const wagonCount = Math.ceil(ticket.weight / wagonLoad);
  const location = `${ticket.trackNo}·${profile.area}`;
  let remaining = ticket.weight;
  const lines = shares.map((share, index) => {
    const plannedQty = index === shares.length - 1 ? remaining : Math.floor(ticket.weight * share / 100);
    remaining -= plannedQty;
    return { lineId: String(index + 1), goodCode: `SIM-MAT-${profile.type}-${index + 1}`, plannedQty, unit: 't',
      batchCode: `SIM-BATCH-${compactDate}-${index + 1}`, supplierCode: `SIM-SUPPLIER-${profile.type}-${1 + (seed + index) % 5}` };
  });
  const details = {
    scenarioCode: ticket.id + '-SCENARIO', scenarioName: `${profile.name}到货 · ${ownerName} · ${ticket.trackNo}${shuttle ? '短驳' : '直卸'}`,
    workSummary: `${ticket.date}，${ticket.trainNo}为${ownerName}运入${ticket.weight}吨${profile.name}，分${materialCount}批、${wagonCount}辆车皮，在${location}按${shuttle ? '车辆短驳' : '直卸'}方案安排作业。`,
    companyCode: 'SIM-COMPANY-' + profile.type, companyName: '模拟' + profile.name + '装卸分公司',
    ownerCode: `SIM-OWNER-${profile.type}-${(seed >>> 4) % profile.owners.length + 1}`, ownerName,
    destinationStation: `模拟${profile.name}作业货场站`, cargoName: profile.name, cargoSpecification: profile.specification,
    priority: ['HIGH', 'MEDIUM', 'LOW'][seed % 3],
    dispatch: {
      plannedWorkAt: `${ticket.date}T12:${35 + seed % 20}:00+08:00`, expectedArrivalAt: ticket.arrivalTime,
      operationLocation: location, channel: `SIM-CHANNEL-${profile.type}-${1 + seed % 4}`,
      transferMode: shuttle ? 'SHUTTLE' : 'DIRECT',
      ...(shuttle ? { vehiclePlate: `SIM-TRUCK-${compactDate}-${resourceNo}`, driverName: '模拟司机' + resourceNo } : {}), lines,
    },
    materialDescriptions: lines.map((line, index) => ({ lineId: line.lineId, goodName: profile.materials[index],
      specification: `${profile.specification}·模拟规格${index + 1}`, packaging: profile.packaging,
      supplierName: `模拟${profile.name}供应商${1 + (seed + index) % 5}` })),
    wagons: Array.from({ length: wagonCount }, (_, index) => ({ wagonNo: `${ticket.id}-CAR-${String(index + 1).padStart(2, '0')}`,
      wagonType: profile.wagonType, plannedWeight: Math.min(wagonLoad, ticket.weight - index * wagonLoad), unit: 't' as const })),
    resources: { equipmentId: ticket.id + '-EQ', equipmentName: `模拟${profile.name}作业设备${resourceNo}`,
      crewCode: `SIM-CREW-${profile.type}-${resourceNo}`, crewName: `模拟${profile.name}班组${resourceNo}`,
      operatorCode: `SIM-OP-${profile.type}-${resourceNo}`, operatorName: '模拟作业员' + resourceNo },
    checkpoints: [`核对${ticket.trainNo}的${wagonCount}辆车皮与${ticket.trackNo}股道`,
      `逐批核对${profile.name}物料及${ticket.weight}吨计划重量`,
      `核对${ownerName}的${materialCount}个到货批次`, `核对${location}的${shuttle ? '短驳车辆和司机安排' : '直卸安排'}`],
  };
  return railwayDetailsSchema.parse(details);
}

export function generateRailwayTicket(date = shanghaiDate()): RailwayTicket {
  z.iso.date().parse(date);
  const seed = createHash('sha256').update(date).digest().readUInt32BE();
  const ticket = {
    version: 'psms.railway-order.v1' as const, simulated: true as const, id: `SIM-RAIL-${date.replaceAll('-', '')}`, date,
    trainNo: `SIM-${10000 + seed % 90000}`, cargoType: cargoProfiles[seed % cargoProfiles.length].type, weight: 800 + seed % 2400,
    workArea: 'AREA-A' as const, trackNo: `T${1 + seed % 4}` as RailwayTicket['trackNo'], sourceStation: '模拟铁路编组站' as const,
    arrivalTime: `${date}T09:00:00+08:00`, departureTime: `${date}T17:00:00+08:00`,
  };
  return railwayTicketSchema.parse({ ...ticket, details: generateRailwayDetails(ticket) });
}
