"""Load explicitly simulated steel records and evidence through the public API."""
import sys,pathlib,json,io,argparse,csv
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parent.parent/'tests'))
from client import Client,ROOT

def sample_pdf():
    content=b'BT /F1 16 Tf 40 760 Td (B PROJECT - SIMULATED QUALITY CERTIFICATE) Tj 0 -36 Td (Steel lot: STEEL-2026-001   Quantity: 100 t) Tj 0 -30 Td (Development fixture. Not an actual quality certificate.) Tj ET'
    objects=[b'<< /Type /Catalog /Pages 2 0 R >>',b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',b'<< /Length '+str(len(content)).encode()+b' >>\nstream\n'+content+b'\nendstream']
    out=bytearray(b'%PDF-1.4\n');offsets=[0]
    for i,obj in enumerate(objects,1):offsets.append(len(out));out.extend(f'{i} 0 obj\n'.encode()+obj+b'\nendobj\n')
    start=len(out);out.extend(f'xref\n0 {len(objects)+1}\n0000000000 65535 f \n'.encode())
    for offset in offsets[1:]:out.extend(f'{offset:010d} 00000 n \n'.encode())
    out.extend(f'trailer << /Size 6 /Root 1 0 R >>\nstartxref\n{start}\n%%EOF'.encode());return bytes(out)

def prepare():
    s=ROOT/'samples';s.mkdir(exist_ok=True)
    pdf=sample_pdf();(s/'quality-simulated.pdf').write_bytes(pdf)
    # A clearly marked diagram fixture, not a claimed photograph of actual work.
    from PIL import Image,ImageDraw
    im=Image.new('RGB',(800,450),'#e8eee7');d=ImageDraw.Draw(im)
    d.rectangle((35,35,765,415),outline='#356851',width=3)
    d.text((65,65),'B PROJECT / SIMULATED HANDOVER EVIDENCE',fill='#234b3d',font_size=28)
    for y in range(160,300,28):d.rounded_rectangle((100,y,700,y+18),radius=8,fill='#87998e',outline='#53685e')
    d.text((65,345),'STEEL-2026-001 | Development sample only',fill='#234b3d',font_size=24)
    stream=io.BytesIO();im.save(stream,format='PNG');png=stream.getvalue();(s/'handover-simulated.png').write_bytes(png)
    definitions=[('ARRIVAL','到货',100),('ACCEPTANCE','验收',100),('WEIGHING','过磅',100),('UNLOADING','卸货',100),('WAREHOUSE_IN','入库',100),('TRANSFER','移库',100),('LOADING','装车',60),('DISPATCH','首次发运',60),('LOADING','装车',40),('DISPATCH','第二次发运',40)]
    templates=[]
    for i,(kind,note,quantity) in enumerate(definitions,1):
        sid=f'STEEL-{i:03d}';batch='STEEL-2026-001';related=[]
        if i>=7:batch+='-60' if i<9 else '-40';related=['STEEL-2026-001']
        previous=6 if i in (7,9) else i-1
        data=dict(sourceEventId=sid,sourceSystem='S2-WMS',eventType=kind,businessObjectId='STEEL-ORDER-001',batchId=batch,occurredAt=f'2026-09-10T{(i+1):02d}:00:00Z',
           quantity=quantity,unit='吨',location='开发验证货场',bundleIds=['BUNDLE-001','BUNDLE-002'] if i<=6 else (['BUNDLE-001'] if i<=8 else ['BUNDLE-002']),relatedBatchIds=related,
           relatedEventRefs=[f'S2-WMS:STEEL-{previous:03d}'] if i>1 else [],evidenceIds=[],
           supplier='模拟供应商甲',receiver='模拟接收单位乙' if i<9 else '模拟接收单位丙',handoverId='HANDOVER-060' if i in [7,8] else ('HANDOVER-040' if i in [9,10] else ''),details={'note':note+'（模拟数据）'})
        templates.append(data)
    (s/'steel-events.json').write_text(json.dumps(templates,ensure_ascii=False,indent=2),encoding='utf-8')
    with (s/'steel-events.csv').open('w',encoding='utf-8-sig',newline='') as stream:
        writer=csv.DictWriter(stream,fieldnames=list(templates[0]));writer.writeheader()
        for row in templates:writer.writerow({k:'|'.join(v) if isinstance(v,list) else (json.dumps(v,ensure_ascii=False) if isinstance(v,dict) else v) for k,v in row.items()})
    (s/'README.md').write_text('# 100 吨钢材模拟样例\n\n质检 PDF 和交接示意图均为开发模拟文件，不是真实质检证书或现场照片。JSON/CSV 是可导入的无附件模板；运行 seed-sample.py 会先上传两个文件，再将返回的证据标识关联到事件。\n\n到货批次含两捆，随后从同一入库来源分为 60 吨和 40 吨两次发运。数量由输入提供，不作为库存自动核算或物料真实性证明。\n',encoding='utf-8')
    return templates,[('quality-simulated.pdf',pdf),('handover-simulated.png',png)]

def seed(base='http://127.0.0.1:18180'):
    templates,files=prepare();c=Client(base=base)
    manifest=ROOT/'.local/sample-result.json';manifest.parent.mkdir(exist_ok=True)
    result=json.loads(manifest.read_text()) if manifest.exists() else {'base':base,'events':[],'evidence':[]}
    if result.get('base',base)!=base:raise ValueError('Sample progress belongs to a different application URL')
    def checkpoint():
        temporary=manifest.with_suffix('.tmp');temporary.write_text(json.dumps(result,indent=2));temporary.replace(manifest)
    for name,body in files[len(result['evidence']):]:
        status,r=c.upload(name,body);assert status==200,(status,r);result['evidence'].append(r['id']);checkpoint()
    for i,template in enumerate(templates):
        data=dict(template,evidenceIds=result['evidence'][:1] if i<6 else result['evidence'])
        # Resubmit cached events too: idempotency confirms a partial run can resume safely.
        _,saved=c.request('POST','/events',data=data,expected=202)
        if i<len(result['events']):assert result['events'][i]==saved['id'],'Application dataset changed; review sample progress before reuse'
        else:result['events'].append(saved['id']);checkpoint()
    for id in result['events']:c.wait(id,timeout=180)
    result['committed']=True;checkpoint();print('Sample committed:',len(result['events']));return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--prepare-only',action='store_true');parser.add_argument('--base',default='http://127.0.0.1:18180');args=parser.parse_args()
    if args.prepare_only:prepare();print('Prepared ten event templates and two explicitly simulated evidence files')
    else:seed(args.base)
