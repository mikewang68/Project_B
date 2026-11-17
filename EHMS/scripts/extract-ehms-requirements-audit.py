"""Read-only source extraction for an EHMS requirements audit. Never saves the input workbook."""
import hashlib,json,pathlib,re,subprocess
import openpyxl
ROOT=pathlib.Path(__file__).resolve().parents[1]
SOURCE=pathlib.Path('C:/Users/madao/Downloads/系统需求功能工作簿.xlsx')
OUT=ROOT/'.local/requirements-audit-20261116'

def main():
 OUT.mkdir(parents=True,exist_ok=True)
 wb=openpyxl.load_workbook(SOURCE,data_only=False)
 def resolve(sheet,cell,seen=()):
  key=(sheet,cell)
  if key in seen:raise ValueError('Circular cell reference')
  value=wb[sheet][cell].value
  if isinstance(value,str):
   m=re.fullmatch(r"='([^']+)'!([A-Z]+[0-9]+)",value)
   if m:return resolve(m[1],m[2],seen+(key,))
  return value
 first,second=wb.worksheets
 relationships=[]
 for row in first.iter_rows(min_row=8):
  if not str(row[2].value or '').startswith('R-E'):continue
  values={c.column_letter:resolve(first.title,c.coordinate) for c in row}
  values.update({'row':row[0].row,'functionTarget':row[5].hyperlink.location if row[5].hyperlink else None,'requirementTarget':row[9].hyperlink.location if row[9].hyperlink else None})
  relationships.append(values)
 requirements={};functions={};current=None
 for row in second:
  a=str(row[0].value or '')
  if re.match(r'^R-[A-Z][0-9]+\s',a):current=a.split()[0]
  if current and current.startswith('R-E'):
   block=requirements.setdefault(current,{'headerRow':row[0].row,'rows':[]})
   if any(c.value is not None for c in row):block['rows'].append({'row':row[0].row,'cells':{c.column_letter:resolve(second.title,c.coordinate) for c in row if c.value is not None}})
  if re.fullmatch(r'F-[A-Z][0-9]+',a):
   # Canonical definitions use literal names; reused entries point at those cells.
   if not str(row[1].value or '').startswith('='):
    functions[a]={'row':row[0].row,'ownerRequirement':current,'cells':{c.column_letter:resolve(second.title,c.coordinate) for c in row if c.value is not None}}
 ids={r['E'] for r in relationships}
 result={'source':str(SOURCE),'sha256':hashlib.sha256(SOURCE.read_bytes()).hexdigest(),'sheets':wb.sheetnames,
  'relationships':relationships,'requirements':requirements,'functions':{k:v for k,v in functions.items() if k in ids}}
 (OUT/'source.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
 text=[]
 for req,b in requirements.items():
  text.append('\n'+req)
  for row in b['rows']:text.append(str(row['row'])+' '+json.dumps(row['cells'],ensure_ascii=False))
 (OUT/'source-EHMS.txt').write_text('\n'.join(text),encoding='utf-8')
 print(json.dumps({'sourceUnchanged':hashlib.sha256(SOURCE.read_bytes()).hexdigest()==result['sha256'],'ownRequirements':len(requirements),'ownRelations':sum(r['A']=='设备健康管理系统' for r in relationships),'allRelatedRelations':len(relationships),'ownUniqueFunctions':sum(k.startswith('F-E') for k in ids),'collaborationFunctions':sorted(k for k in ids if not k.startswith('F-E'))},ensure_ascii=False))
if __name__=='__main__':main()
