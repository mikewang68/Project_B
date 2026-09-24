"""Single Nginx gateway; the application accepts HTTP directly behind a firewall."""
import ipaddress
import pathlib
import re
from gateway_portal import site_root, validate_portal

ROLES = ('gateway', 'application')
MARKER = '# Managed by B-PROJECT-TRUST HTTP access\n'


def validate(config):
    access = config.get('httpAccess')
    if not isinstance(access, dict):
        raise ValueError('Missing httpAccess configuration; see deploy/http-access.example.json')
    gateway = access.get('gateway', {})
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_.-]*', str(gateway.get('sshAlias', ''))):
        raise ValueError('Invalid HTTP gateway SSH alias')
    if gateway['sshAlias'] in [n['sshAlias'] for n in config['nodes'].values()]:
        raise ValueError('HTTP gateway SSH alias must be separate from component aliases')
    address = str(ipaddress.IPv4Address(gateway.get('address', '')))
    if address in [n['address'] for n in config['nodes'].values()]:
        raise ValueError('HTTP gateway must be separate from the four component nodes')
    for key in ('listenAddresses', 'clientCidrs'):
        values = gateway.get(key)
        if not isinstance(values, list) or not values or any(not isinstance(v, str) for v in values) or len(values) != len(set(values)):
            raise ValueError('HTTP gateway requires unique ' + key)
        for value in values:
            network = ipaddress.IPv4Network(value if key == 'clientCidrs' else str(ipaddress.IPv4Address(value)) + '/32', strict=True)
            if network.prefixlen == 0 or network.is_multicast or network.is_unspecified or network.is_loopback:
                raise ValueError('HTTP access requires explicit non-loopback addresses and client networks')
    ports = gateway.get('ports')
    if (not isinstance(ports, dict) or set(ports) != {'public', 'trust', 'internal'}
            or any(type(value) is not int or not 1 <= value <= 65535 for value in ports.values())
            or len(set(ports.values())) != 3 or ports['public'] != 80):
        raise ValueError('Gateway ports must define unique public=80, trust and internal TCP ports')
    if address not in gateway['listenAddresses'] or gateway.get('accessAddress') not in gateway['listenAddresses']:
        raise ValueError('Gateway listenAddresses must include address and accessAddress')
    validate_portal(config)
    return access


def settings(config, role):
    if role not in ROLES:
        raise ValueError('Unknown HTTP access role')
    access = validate(config)
    gateway = access['gateway']
    if role == 'gateway':
        return {'addresses': gateway['listenAddresses'], 'ports': gateway['ports'],
                'clients': gateway['clientCidrs'], 'upstream': config['nodes']['application']['address'] + ':28182'}
    return {'addresses': [config['nodes']['application']['address']], 'port': 28182,
            'clients': [gateway['address'] + '/32'], 'listenAddress': '0.0.0.0'}


def workdir(config, role):
    return pathlib.PurePosixPath(config['remoteRoot']) / 'runtime/http-access' / role


def render_nginx(config, role='gateway', *, preview=False):
    if role != 'gateway':
        raise ValueError('Only the gateway runs Nginx; the application listens directly')
    spec = settings(config, 'gateway')
    folder = workdir(config, 'gateway') / 'preview'
    if preview:
        spec = dict(spec, addresses=['127.0.0.1'],
                    ports={'public': 28280, 'trust': 28281, 'internal': 28282},
                    upstream='127.0.0.1:28182')
    clients = list(dict.fromkeys(['127.0.0.1/32', config['httpAccess']['gateway']['address'] + '/32'] + spec['clients']))
    allows = '\n'.join('    allow ' + client + ';' for client in clients)
    log = str(folder) if preview else '/var/log/nginx'
    portal = validate_portal(config)

    def listen_lines(port):
        return '\n'.join('    listen ' + address + ':' + str(port) + ' default_server;'
                         for address in spec['addresses'])

    def static_server(name, audience):
        port = spec['ports'][audience]
        routes = '    location / { return 404; }\n'
        if portal:
            routes = f'''    root {site_root(config, preview, audience=audience)};
    types {{ text/html html; text/css css; application/javascript js; application/json json; }}
    charset utf-8;
    location = / {{
        try_files /index.html =404;
        add_header Cache-Control "no-store";
    }}
    location ^~ /portal-assets/ {{
        try_files $uri =404;
        add_header Cache-Control "no-store";
    }}
    location / {{ return 404; }}
'''
        return f'''server {{
{listen_lines(port)}
    server_name _;
    server_tokens off;
    access_log {log}/b-project-{name}-access.log;
    error_log {log}/b-project-{name}-error.log warn;
{allows}
    deny all;
    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options DENY always;
    add_header Referrer-Policy same-origin always;
{routes}}}
'''

    trust_server = f'''server {{
{listen_lines(spec['ports']['trust'])}
    server_name _;
    server_tokens off;
    client_max_body_size 21m;
    access_log {log}/b-project-trust-access.log;
    error_log {log}/b-project-trust-error.log warn;
{allows}
    deny all;
    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options DENY always;
    add_header Referrer-Policy same-origin always;
    location / {{
        proxy_pass http://{spec['upstream']}/;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $http_host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Forwarded "";
        proxy_set_header X-Real-IP "";
        proxy_connect_timeout 5s;
        proxy_read_timeout 180s;
        proxy_send_timeout 180s;
    }}
}}
'''
    servers = (static_server('public-portal', 'public') + trust_server
               + static_server('internal-portal', 'internal'))
    if not preview:
        return MARKER + servers
    temp = '\n'.join('    ' + name + '_temp_path ' + str(folder / name) + ';'
                     for name in ('client_body', 'proxy', 'fastcgi', 'uwsgi', 'scgi'))
    return MARKER + f'''worker_processes 1;
pid {folder}/nginx.pid;
error_log {folder}/error.log warn;
events {{ worker_connections 1024; }}
http {{
{temp}
{servers}
}}
'''


def unit_name(role):
    if role not in ROLES:
        raise ValueError('Unknown HTTP access role')
    return 'b-project-trust-http-' + role + '.service'


def firewall_rules(config, role, address):
    spec = settings(config, role)
    if address not in spec['addresses']:
        raise ValueError('Firewall destination does not belong to this role')
    # The Java listener is IPv4-only and also retains loopback for existing tools.
    # Protect its port throughout the external zone, including secondary addresses.
    destination = f'destination address="{address}/32" ' if role == 'gateway' else ''
    ports = spec['ports'].values() if role == 'gateway' else [spec['port']]
    rules = []
    for port in ports:
        target = destination + f'port port="{port}" protocol="tcp"'
        rules.extend(f'rule family="ipv4" priority="-20" source address="{client}" {target} accept'
                     for client in spec['clients'])
        rules.append(f'rule family="ipv4" priority="-10" {target} reject')
    return rules
