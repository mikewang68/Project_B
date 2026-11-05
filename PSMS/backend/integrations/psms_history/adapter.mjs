/** PSMS simulation intake. Business effects, event deduplication and cursor share one transaction. */
import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';

export const PROJECT = 'SIM-B-INTEGRATION';
// PSMS work-area syntax is AREA-*. Keep the approved logical simulation area in the manifest.
export const WORK_AREA = 'AREA-SIM-YAN-EQUIPMENT';
export const ENDPOINT = 'bn4:b-device-ingress-sim.service:/api/device-data/v1:legacy';
export function mappingHash(mapping) {
  return createHash('sha256').update(JSON.stringify(mapping)).digest('hex');
}
function requireValue(value, message) { if (!value) throw new Error(message); }
export function validateReceipt(item, receipt, mapping) {
  const event = receipt?.event;
  requireValue(item && event && receipt.state === 'COMMITTED', 'uncommitted event');
  requireValue(event.schema_version === 'b.device-event.v1' && event.simulated === true, 'only declared simulation supported');
  requireValue(typeof event.event_id === 'string' && event.event_id ===
    [event.run_id,event.boot_id,event.device_id,event.seq].join(':'), 'event identity mismatch');
  requireValue(Number.isSafeInteger(event.seq) && event.seq > 0, 'invalid sequence');
  requireValue(event.event_id === item.event_id && receipt.event_id === item.event_id, 'receipt identity mismatch');
  requireValue(mapping.deviceId === event.device_id, 'device mismatch');
  requireValue(typeof item.wire_sha256 === 'string' && /^[a-f0-9]{64}$/.test(item.wire_sha256)
    && receipt.wire_sha256 === item.wire_sha256, 'wire hash mismatch');
  requireValue(typeof receipt.broker_message_id === 'string' && receipt.broker_message_id.length > 0
    && !receipt.broker_message_id.startsWith('LOCAL_'), 'real broker receipt required');
  requireValue(!item.broker_message_id || item.broker_message_id === receipt.broker_message_id, 'broker mismatch');
  requireValue(typeof item.cursor === 'string' && item.cursor.length > 0 && item.cursor.length <= 4096, 'invalid cursor');
  requireValue(Number.isSafeInteger(item.committed_ms) && item.committed_ms > 0, 'invalid commit position');
  requireValue(receipt.committed_ms === item.committed_ms, 'commit position mismatch');
  let position;
  try { position=JSON.parse(Buffer.from(item.cursor,'base64url').toString('utf8')); } catch { throw new Error('invalid cursor'); }
  requireValue(Array.isArray(position) && position[0]===1 && position[1]===mapping.deviceId
    && position[2]===item.committed_ms && position[3]===item.event_id,'cursor identity mismatch');
  requireValue(Number.isSafeInteger(event.observed_ts_ms) && event.observed_ts_ms > 0, 'invalid observation time');
  requireValue(event.source_ts_ms === null || Number.isSafeInteger(event.source_ts_ms), 'invalid source time');
  requireValue(Array.isArray(event.payload?.points) && event.payload.points.length <= 256, 'invalid point list');
  const byCode = new Map();
  for (const point of event.payload.points) {
    requireValue(typeof point.point_code === 'string' && !byCode.has(point.point_code), 'duplicate point');
    requireValue(Object.hasOwn(point,'value') && Object.hasOwn(point,'unit')
      && ['GOOD','UNCERTAIN','BAD','STALE'].includes(point.quality_code)
      && point.provenance && typeof point.provenance === 'object' && !Array.isArray(point.provenance), 'invalid point');
    byCode.set(point.point_code,point);
  }
  requireValue(byCode.size === mapping.allPointCodes.length && mapping.allPointCodes.every(code => byCode.has(code)),
    'full source point set changed');
  const selected = mapping.pointCodes.map(code => byCode.get(code));
  requireValue(selected.every(Boolean), 'required business points missing');
  return selected;
}

export async function registerMappings(db, mappings) {
  await db.query('BEGIN');
  try {
    for (const mapping of mappings) {
      const hash=mappingHash(mapping), marker={simulated:true,project:PROJECT,sourceDeviceId:mapping.deviceId};
      const found=(await db.query('SELECT id,equipment_id,work_area,specs,valid FROM equipment WHERE equipment_id=$1 OR id=$1 FOR UPDATE',[mapping.deviceId])).rows;
      if (found.length) {
        requireValue(found.length===1 && found[0].id===mapping.deviceId && found[0].equipment_id===mapping.deviceId
          && found[0].work_area===WORK_AREA && Number(found[0].valid)===1
          && found[0].specs?.simulated===true && found[0].specs?.project===PROJECT
          && found[0].specs?.sourceDeviceId===mapping.deviceId,'existing device is not this simulation binding');
      } else {
        await db.query("INSERT INTO equipment(id,equipment_id,name,type,model,specs,work_area,status,valid) VALUES($1,$1,$2,$3,'YAN-SIM-POINTS-v1',$4::jsonb,$5,'OFFLINE',1)",
          [mapping.deviceId,'[模拟] '+mapping.name,mapping.type,JSON.stringify(marker),WORK_AREA]);
      }
      const old=(await db.query('SELECT * FROM psms_device_binding WHERE device_id=$1 FOR UPDATE',[mapping.deviceId])).rows[0];
      if(old)requireValue(old.mapping_hash===hash && old.equipment_id===mapping.deviceId && old.work_area===WORK_AREA
        && old.project_id===PROJECT && old.enabled===true
        && old.point_codes===JSON.stringify(mapping.pointCodes),'binding change requires explicit migration');
      else await db.query('INSERT INTO psms_device_binding(device_id,equipment_id,work_area,project_id,mapping_hash,point_codes) VALUES($1,$1,$2,$3,$4,$5)',
        [mapping.deviceId,WORK_AREA,PROJECT,hash,JSON.stringify(mapping.pointCodes)]);
      const checkpoint=(await db.query('SELECT endpoint_identity FROM psms_device_checkpoint WHERE device_id=$1',[mapping.deviceId])).rows[0];
      if(checkpoint)requireValue(checkpoint.endpoint_identity===ENDPOINT,'endpoint identity change');
      else await db.query('INSERT INTO psms_device_checkpoint VALUES($1,$2,NULL)',[mapping.deviceId,ENDPOINT]);
    }
    await db.query('COMMIT');
  } catch(error){await db.query('ROLLBACK');throw error}
}

export async function applyItem(db, mapping, expectedCursor, item, receipt, failAfterPoints=false) {
  const points=validateReceipt(item,receipt,mapping);
  await db.query('BEGIN');
  try {
    const binding=(await db.query('SELECT b.*,c.cursor_value,c.endpoint_identity FROM psms_device_binding b JOIN psms_device_checkpoint c ON c.device_id=b.device_id WHERE b.device_id=$1 FOR UPDATE',[mapping.deviceId])).rows[0];
    requireValue(binding && binding.enabled===true && binding.mapping_hash===mappingHash(mapping)
      && binding.work_area===WORK_AREA && binding.project_id===PROJECT && binding.equipment_id===mapping.deviceId
      && binding.point_codes===JSON.stringify(mapping.pointCodes) && binding.endpoint_identity===ENDPOINT,'binding disabled or changed');
    requireValue(binding.cursor_value===expectedCursor,'checkpoint moved; refetch page');
    if(expectedCursor){
      const previous=JSON.parse(Buffer.from(expectedCursor,'base64url').toString('utf8'));
      requireValue(item.committed_ms>previous[2] || (item.committed_ms===previous[2] && item.event_id>=previous[3]),'cursor regression');
    }
    const old=(await db.query('SELECT device_id,wire_sha256,receipt_json FROM psms_device_inbox WHERE event_id=$1',[item.event_id])).rows[0];
    if(old)requireValue(old.wire_sha256===item.wire_sha256 && old.device_id===mapping.deviceId
      && isDeepStrictEqual(JSON.parse(old.receipt_json).event,receipt.event),'historical event conflict');
    else {
      await db.query('INSERT INTO psms_device_inbox(event_id,device_id,wire_sha256,broker_message_id,committed_ms,observed_ms,source_ms,receipt_json) VALUES($1,$2,$3,$4,$5,$6,$7,$8)',
        [item.event_id,mapping.deviceId,item.wire_sha256,receipt.broker_message_id,item.committed_ms,receipt.event.observed_ts_ms,receipt.event.source_ts_ms,JSON.stringify(receipt)]);
      for(const point of points)await db.query('INSERT INTO psms_device_reading VALUES($1,$2,$3,$4,$5,$6)',
        [item.event_id,point.point_code,JSON.stringify(point.value),JSON.stringify(point.unit),point.quality_code,JSON.stringify(point.provenance)]);
      if(failAfterPoints)throw new Error('INJECTED_TEST_TRANSACTION_FAILURE');
      // No task transitions, device availability changes or writes into numeric-only telemetry.
      const updated=await db.query('UPDATE equipment SET last_telemetry_at=CASE WHEN last_telemetry_at IS NULL OR last_telemetry_at<$2::timestamptz THEN $2::timestamptz ELSE last_telemetry_at END,updated_at=now() WHERE id=$1 AND work_area=$3 AND valid=1 AND specs->>\'project\'=$4 AND specs->>\'simulated\'=\'true\'',
        [mapping.deviceId,new Date(receipt.event.observed_ts_ms).toISOString(),WORK_AREA,PROJECT]);
      requireValue(updated.rowCount===1,'simulation equipment binding unavailable');
    }
    await db.query('UPDATE psms_device_checkpoint SET cursor_value=$2 WHERE device_id=$1',[mapping.deviceId,item.cursor]);
    await db.query('COMMIT');
    return {eventId:item.event_id,duplicate:!!old,businessPoints:points.length,cursor:item.cursor};
  } catch(error){await db.query('ROLLBACK');throw error}
}

/** Attach only to equipment already selected by PSMS's existing authenticated area-scoped query. */
export async function attachObservations(db, equipment) {
  const ids=equipment.filter(row=>row.work_area===WORK_AREA).map(row=>row.id);
  if(!ids.length)return equipment;
  const latest=(await db.query('SELECT * FROM (SELECT i.*,row_number() OVER(PARTITION BY i.device_id ORDER BY i.observed_ms DESC,i.event_id DESC) AS rank FROM psms_device_inbox i JOIN psms_device_binding b ON b.device_id=i.device_id WHERE b.enabled=true AND b.equipment_id=ANY($1::text[]) AND b.work_area=$2 AND b.project_id=$3) s WHERE rank=1',[ids,WORK_AREA,PROJECT])).rows;
  const observations=new Map();
  for(const event of latest){
    const rows=(await db.query('SELECT * FROM psms_device_reading WHERE event_id=$1 ORDER BY point_code',[event.event_id])).rows;
    observations.set(event.device_id,{simulated:true,projectId:PROJECT,sourceDeviceId:event.device_id,
      sourceEventId:event.event_id,wireSha256:event.wire_sha256,observedTsMs:Number(event.observed_ms),
      sourceTsMs:event.source_ms===null?null:Number(event.source_ms),
      points:rows.map(p=>({point_code:p.point_code,value:JSON.parse(p.value_json),unit:JSON.parse(p.unit_json),
        quality_code:p.quality_code,provenance:JSON.parse(p.provenance_json)}))});
  }
  return equipment.map(row=>row.work_area===WORK_AREA?{...row,simulated:true,deviceObservation:observations.get(row.id)??null}:row);
}
