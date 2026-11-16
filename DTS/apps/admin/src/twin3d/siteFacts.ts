/**
 * 中铁二院《给大连大.dwg》施工图与第 20 次组会“一图一文档”工程事实真值清单（V4）
 */
export type SiteFactStatus = 'confirmed' | 'position-review' | 'inferred' | 'inferred-proportional'

export interface SiteFact {
  id: string
  category: 'rail' | 'equipment' | 'storage' | 'process'
  name: string
  statement: string
  status: SiteFactStatus
  source: 'annotated-steel-layout-plan' | 'cad-dxf-geometry'
}

export const SITE_FACTS: SiteFact[] = [
  {
    id: 'FACT-REBAR-RAIL-LINE',
    category: 'rail',
    name: '钢材装卸线（货3/货4 + 3×40t门吊）',
    statement: '货3（560m）与货4（564m）紧邻钢筋仓库南侧；3台 Q=40t, S=30m, 370kW 门吊（CR-01~03）跨越两轨及库外交接区。',
    status: 'confirmed',
    source: 'cad-dxf-geometry',
  },
  {
    id: 'FACT-REBAR-WAREHOUSE',
    category: 'storage',
    name: '钢筋仓库（23,882 m² · 13跨桥吊）',
    statement: '按给大连大.dwg 实测 13 跨布局，轨顶标高 13.0m：含 9 台 20t 磁吸桥吊（编号 15）与 4 台 32t C 型吊具桥吊（编号 16），与门吊联合吊装。',
    status: 'confirmed',
    source: 'cad-dxf-geometry',
  },
  {
    id: 'FACT-ASH-RAIL-AND-TIPPER',
    category: 'process',
    name: '散料线（货1/货2 + 翻箱机 + 卸料室 + 提升机）',
    statement: '货1（950m）与货2（790m）配 3 台 375kW 集装箱门吊及 4 套“移动翻箱机 + 半封闭卸料室（42m³料斗）+ 提升机 + 空气斜槽”。',
    status: 'confirmed',
    source: 'cad-dxf-geometry',
  },
  {
    id: 'FACT-ASH-40-SILOS',
    category: 'storage',
    name: '40 座 3000T 筒仓与库底定量装车',
    statement: '实测 4 组 40 座 Φ12m、直筒 18m、总高 33.38m、柱腿+1.00m 钢板筒仓（水泥+粉煤灰，总容 12 万吨），库底配 40 套伸缩散装定量装车设备及 2×100t 地磅。',
    status: 'confirmed',
    source: 'cad-dxf-geometry',
  },
]

export const SITE_FACT_STATUS_LABEL: Record<SiteFactStatus, string> = {
  confirmed: '施工图实测已确认',
  'position-review': '待现场复核',
  inferred: '工艺推断',
  'inferred-proportional': '比例估算',
}
