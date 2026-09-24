"""Build the gateway navigation site from private deployment service entries."""
import hashlib
import json
import pathlib
import re
import urllib.parse

SOURCE = pathlib.Path(__file__).resolve().parent / 'portal'
CATEGORIES = ('业务系统', '公共能力', '运维工具')


def validate_portal(config):
    portal = config.get('httpAccess', {}).get('portal')
    if portal is None:
        return None
    if not isinstance(portal, dict) or not isinstance(portal.get('services'), list):
        raise ValueError('portal.services must be a list')
    if not 1 <= len(portal['services']) <= 40:
        raise ValueError('Portal requires between 1 and 40 entries')
    seen = set()
    for service in portal['services']:
        if not isinstance(service, dict) or not re.fullmatch(r'[a-z][a-z0-9-]{0,39}', service.get('id', '')):
            raise ValueError('Invalid portal service id')
        if service['id'] in seen:
            raise ValueError('Duplicate portal service id')
        seen.add(service['id'])
        for key, limit in [('title', 60), ('description', 180), ('badge', 30), ('code', 12)]:
            if not isinstance(service.get(key), str) or not 1 <= len(service[key]) <= limit:
                raise ValueError('Invalid portal service ' + key)
        if service.get('category') not in CATEGORIES:
            raise ValueError('Invalid portal category')
        if 'enabled' in service and type(service['enabled']) is not bool:
            raise ValueError('Portal service enabled must be boolean')
        if 'publicDescription' in service and (not isinstance(service['publicDescription'], str)
                                               or not 1 <= len(service['publicDescription']) <= 180):
            raise ValueError('Invalid portal public description')
        public_url = service.get('publicUrl')
        if public_url is not None:
            if not isinstance(public_url, str) or len(public_url) > 240:
                raise ValueError('Invalid portal public URL')
            parsed = urllib.parse.urlsplit(public_url)
            if (parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password
                    or parsed.query or parsed.fragment or not parsed.path.startswith('/')):
                raise ValueError('Portal public URLs must be absolute HTTPS URLs without credentials, query or fragment')
        if service['id'] == 'trust':
            continue
        if service.get('scheme') not in ('http', 'https'):
            raise ValueError('Portal entries must use HTTP or HTTPS')
        if type(service.get('port')) is not int or not 1 <= service['port'] <= 65535:
            raise ValueError('Invalid portal service port')
        if not re.fullmatch(r'/[A-Za-z0-9_./-]*', service.get('path', '/')) or '..' in service.get('path', '/').split('/'):
            raise ValueError('Invalid portal service path')
    if 'trust' not in seen:
        raise ValueError('Portal must retain the TRUST entry')
    return portal


def site_files(config, audience='internal'):
    if audience not in ('public', 'internal'):
        raise ValueError('Unknown portal audience')
    portal = validate_portal(config)
    if portal is None:
        return {}
    address = config['nodes']['application']['address']
    gateway = config['httpAccess']['gateway']
    entries = []
    for service in portal['services']:
        if service.get('enabled', True) is not True or (audience == 'public' and not service.get('publicUrl')):
            continue
        keys = ('id', 'title', 'code', 'category') if audience == 'public' else ('id', 'title', 'badge', 'code', 'category')
        entry = {key: service[key] for key in keys}
        entry['description'] = service.get('publicDescription', service['description']) if audience == 'public' else service['description']
        if audience == 'public':
            parsed = urllib.parse.urlsplit(service['publicUrl'])
            entry.update(url=service['publicUrl'], endpoint=parsed.hostname, protocol='HTTPS')
        elif service['id'] == 'trust':
            port = gateway['ports']['trust']
            entry.update(url=f'http://{gateway["accessAddress"]}:{port}/',
                         endpoint=f'独立入口 · 端口 {port}', protocol='HTTP')
        else:
            entry.update(url=f'{service["scheme"]}://{address}:{service["port"]}{service.get("path", "/")}',
                         endpoint=f'端口 {service["port"]}', protocol=service['scheme'].upper())
        entries.append(entry)
    categories = [category for category in CATEGORIES if any(item['category'] == category for item in entries)]
    html = (SOURCE / 'index.html').read_text(encoding='utf-8')
    html = html.replace('data-audience="internal"', f'data-audience="{audience}"', 1)
    data = {'audience': audience, 'services': entries, 'categories': categories}
    return {'index.html': html.encode('utf-8'),
            'portal-assets/portal.css': (SOURCE / 'portal.css').read_bytes(),
            'portal-assets/portal.js': (SOURCE / 'portal.js').read_bytes(),
            'portal-assets/services.json': json.dumps(data, ensure_ascii=False, indent=2).encode('utf-8')}


def release_id(files):
    values = {name: hashlib.sha256(value).hexdigest() for name, value in files.items()}
    return hashlib.sha256(json.dumps(values, sort_keys=True).encode()).hexdigest()[:16]


def site_root(config, preview=False, *, audience='internal'):
    if preview:
        return pathlib.PurePosixPath(config['remoteRoot']) / ('runtime/http-access/gateway/portal-' + audience + '-preview')
    return pathlib.PurePosixPath('/usr/share/nginx/html/b-project-portal') / audience / release_id(site_files(config, audience))


def write_site(destination, files):
    destination = pathlib.Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    for name, data in files.items():
        file = destination / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(data)
