"""Read one committed simulated-device event using a private token file."""
import argparse
import json
from pathlib import Path
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, ProxyHandler, HTTPRedirectHandler, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config',type=Path)
    p.add_argument('--base-url')
    p.add_argument('--token-file',type=Path)
    p.add_argument('--event-id')
    a=p.parse_args();config=json.loads(a.config.read_text(encoding='utf-8')) if a.config else {}
    base=a.base_url or config.get('base_url');token_path=a.token_file or config.get('token_file')
    event_id=a.event_id or config.get('sample_event_id')
    if not base or not token_path or not event_id:p.error('provide --config or all three connection arguments')
    endpoint=urlsplit(base)
    if endpoint.scheme!='http' or endpoint.hostname!='127.0.0.1' or not endpoint.port or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment or endpoint.path not in ('','/'):
        p.error('only an explicit HTTP loopback endpoint is supported')
    token=Path(token_path).read_text(encoding='utf-8').strip()
    url=base.rstrip('/')+'/api/device-data/v1/events?'+urlencode({'event_id':event_id})
    request=Request(url,headers={'Authorization':'Bearer '+token})
    with build_opener(ProxyHandler({}),NoRedirect()).open(request,timeout=10) as response:
        event=json.load(response)
    if event.get('state')!='COMMITTED' or event.get('event_id')!=event_id:
        raise RuntimeError('event not committed or identity mismatch')
    points=event['event']['payload']['points']
    print(json.dumps({'event_id':event_id,'state':event['state'],'broker_message_id':event['broker_message_id'],
        'wire_sha256':event['wire_sha256'],'points':len(points),'source_ts_ms':event['event'].get('source_ts_ms'),
        'observed_ts_ms':event['event']['observed_ts_ms'],
        'net_weight':next((x for x in points if x['point_code']=='S1-PT-IMG-07-023'),None)},ensure_ascii=True,indent=2))


if __name__=='__main__':main()
