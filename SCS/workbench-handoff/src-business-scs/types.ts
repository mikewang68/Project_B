/** 交付快照结构（catalog-snapshot.json），与 db/v21/003 绑定种子一致。 */
export interface CatalogDevice {
  device_id: string
  name: string
  template_id: string
  points: string[]
  scs_domain: 'RADAR' | 'UWB' | 'AI' | 'DEVICE' | 'ZONE' | 'OBSERVE'
  scs_object_code: string
  scenarios: string[]
}
