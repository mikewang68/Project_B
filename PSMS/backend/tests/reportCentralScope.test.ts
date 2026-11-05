import { describe, expect, it, vi } from 'vitest';
const {query}=vi.hoisted(()=>({query:vi.fn(async()=>({rows:[]}))}));
vi.mock('../src/db/openGaussClient.js',()=>({getPool:()=>({query})}));
vi.mock('../src/lib/logger.js',()=>({logger:{error:vi.fn()}}));
import {buildFilters,type UserContext} from '@bproject/iam-context-client';
import {centralAuditScope,centralRoute,runWithCentralScope} from '../src/middleware/central-scope.js';
import {generateReport} from '../src/services/report.service.js';
function context():UserContext {
  const triple={projectId:'PROJECT_B',regionId:'SHEN_TIE',subsystemCode:'PSMS'};
  return {...buildFilters('SCOPED',[triple]),...triple,contextVersion:2,
    userId:'report-user',userCode:'report-user',username:'reader',displayName:'reader',
    adminLevel:'L4_OPERATOR',readOnly:true,serviceAudience:'PSMS',scopeMode:'SCOPED',
    allowedScopes:[triple],activeScope:[triple],roles:['report-reader'],
    visibleMenuKeys:['PSMS:ui-011'],menuOperations:{'PSMS:ui-011':['VIEW','EXPORT']},
    managementCapabilities:[],authorizationVersion:'1'};
}
describe('PSMS reports enforce both scopes for aggregate reads and export',()=>{
  it('uses the original area and dates and appends bound central filters to every aggregate',async()=>{
    query.mockClear();
    await runWithCentralScope(context(),()=>generateReport({startDate:'2026-11-01',endDate:'2026-11-01',
      workAreas:['AREA-A']}));
    expect(query.mock.calls).toHaveLength(4);
    for(const [sql,params] of query.mock.calls as unknown as [string,unknown[]][]) {
      expect(sql).toContain('work_area = ANY($1::text[])');
      expect(sql).toMatch(/project_id = \$\d+ AND region_id = \$\d+ AND subsystem_code = \$\d+/);
      expect(params.slice(-3)).toEqual(['PROJECT_B','SHEN_TIE','PSMS']);
      expect(params[0]).toEqual(['AREA-A']);
      expect(sql.indexOf('region_id')).toBeLessThan(sql.indexOf('GROUP BY'));
    }
  });
  it('allows explicitly granted read-only export while denying any edit action',()=>{
    expect(()=>centralRoute(context(),'POST','/api/governance/reports/export')).not.toThrow();
    expect(()=>centralRoute({...context(),menuOperations:{'PSMS:ui-011':['VIEW']}},
      'POST','/api/governance/reports/export')).toThrow('未授予');
    const attribution=runWithCentralScope(context(),centralAuditScope);
    expect(attribution).toEqual({projectId:'PROJECT_B',regionId:'SHEN_TIE',subsystemCode:'PSMS'});
  });
});
