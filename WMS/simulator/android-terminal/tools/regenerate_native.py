"""Regenerate only the C engineering register table from the reviewed device-points.json."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
mapping = json.loads((ROOT / 'device-points.json').read_text(encoding='utf-8'))
definitions = []
for p in mapping['points']:
    if p['module'] != 'S2' or p['source_kind'] != 'engineering':
        continue
    device = {'EQ-IMG-06': 1, 'EQ-IMG-09': 2, 'EQ-IMG-12': 3}[p['equipment']]
    kind = {'bool16': 0, 'uint16': 1, 'float32-be': 2, 'ascii32': 3}[p['encoding']]
    definitions.append('{%d,%d,%d,%d,%d,%.8f,%s}' % (device, p['address'], p['words'], kind, p['model'], p['normal'], json.dumps(p['text'])))
(ROOT / 'native/device_defs.h').write_text('#ifndef WMS_DEVICE_DEFS_H\n#define WMS_DEVICE_DEFS_H\ntypedef struct {int device;unsigned address,words;int kind,model;double normal;const char *text;} wms_def;\nstatic const wms_def wms_defs[]={\n' + ',\n'.join(definitions) + '\n};\n#define WMS_DEF_COUNT (sizeof(wms_defs)/sizeof(wms_defs[0]))\n#endif\n', encoding='utf-8')
print('C engineering definitions:', len(definitions))
