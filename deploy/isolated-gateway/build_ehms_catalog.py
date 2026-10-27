"""Generate reviewable menu migration from the actual EHMS frontend manifest.

No page is automatically approved. Technical review must explicitly promote
validated rows and add permission rules before their launch is enabled.
"""
import hashlib
import pathlib
import re


def catalog(root):
    text = (root / 'EHMS/web/app.js').read_text(encoding='utf-8').split('let EQUIPMENT', 1)[0]
    stages = (root / 'EHMS/web/sample-workbench.js').read_text(encoding='utf-8')
    complete = set(re.findall(r"'([^']+)'", re.search(r'const complete=new Set\(\[(.*?)\]\)', stages, re.S)[1]))
    partial = set(re.findall(r"'([^']+)'", re.search(r'const partial=new Set\(\[(.*?)\]\)', stages, re.S)[1]))
    groups = []
    for match in re.finditer(r"\{ id:'([^']+)', icon:'[^']*', label:'([^']+)', children:\[(.*?)\n  \]\}", text, re.S):
        group_id, label, children = match.groups()
        pages = [(m[0], m[1]) for m in re.findall(r"\['([^']+)','([^']+)'(?:,'[^']*')?\]", children)]
        groups.append((group_id, label, pages))
    if len(groups) != 10 or sum(len(g[2]) for g in groups) != 66:
        raise ValueError('EHMS manifest changed; review parser and page classification')
    return groups, complete, partial


def generate(root):
    groups, complete, partial = catalog(root)
    def q(v): return "'" + str(v).replace("'", "''") + "'"
    columns = ['stable_menu_key','navigation_app_code','source_app_code','source_menu_id','source_parent_id',
        'parent_stable_menu_key','hierarchy_code','original_level','normalized_level','sibling_sort_order',
        'menu_name','projected_display_title','menu_kind','entry_mode','route_path','entry_path','page_key',
        'visible','enabled','valid','implementation_status','source_reference','content_sha256',
        'last_batch_id','feature_status','unavailable_reason','nav_eligible']
    statements = ['-- Generated from EHMS/web/app.js and sample-workbench.js. No automatic release approval.',
        'BEGIN;', 'SET LOCAL search_path TO iam, public;']
    def row(key, source, parent, level, order, label, kind, path, status, reason):
        values = [key,'EHMS','EHMS',source,parent,parent,'EHMS.CATALOG.'+source,level,level,order,
            label,label,kind,'SPA_ROUTE' if kind=='PAGE' else 'NONE',path,path,source if kind=='PAGE' else '',
            1,1,1,'UNKNOWN','EHMS/web/app.js#'+source,hashlib.sha256((key+path).encode()).hexdigest(),
            'EHMS_CATALOG_V1',status,reason,0]
        literal = lambda v: str(v) if isinstance(v,int) else q(v)
        statements.append('INSERT INTO iam_navigation_menu ('+', '.join(columns)+') SELECT '+', '.join(map(literal,values))
            +' WHERE NOT EXISTS (SELECT 1 FROM iam_navigation_menu WHERE stable_menu_key='+q(key)+');')
        # Existing cockpit is preserved, moved to its actual group, and receives a real deep link.
        statements.append('UPDATE iam_navigation_menu SET parent_stable_menu_key='+q(parent)
            +', normalized_level='+str(level)+', sibling_sort_order='+str(order)+', entry_path='+q(path)
            +', route_path='+q(path)+', page_key='+q(source if kind=='PAGE' else '')
            +', feature_status='+q(status)+', unavailable_reason='+q(reason)
            +' WHERE stable_menu_key='+q(key)+" AND (last_batch_id='EHMS_CATALOG_V1' OR stable_menu_key='EHMS:cockpit');")
    for i,(gid,label,pages) in enumerate(groups,1):
        parent='EHMS:dir-'+gid
        row(parent,gid,'APP:EHMS',2,i*100,label,'DIRECTORY','','READY','')
        for j,(pid,title) in enumerate(pages,1):
            status='PENDING_VERIFICATION' if pid in complete else 'PARTIAL' if pid in partial else 'PLANNED'
            reason='业务接口已有实现，等待当前发布版本验收' if pid in complete else '部分能力已实现，等待实际页面与接口验收' if pid in partial else '设计功能，业务执行尚未接入'
            row('EHMS:cockpit' if pid=='dashboard' else 'EHMS:'+pid,pid,parent,3,j*10,title,'PAGE','/ehm/?page='+pid,status,reason)
    statements += ['COMMIT;', '']
    return '\n'.join(statements)


if __name__ == '__main__':
    root=pathlib.Path(__file__).resolve().parents[2]
    target=root/'deploy/isolated-gateway/sql/025-ehms-feature-catalog.sql'
    target.write_text(generate(root),encoding='utf-8')
    print('Generated 10 directories and 66 business pages; approval remains disabled.')
