import { Alert, Button, Input, Space, Tag } from 'antd';
import { useEffect, useState } from 'react';

const key = 'psms:backend-access-token';
export function useBackendConnection() {
  const [token, setToken] = useState(() => sessionStorage.getItem(key) ?? '');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    const refresh = () => setToken(sessionStorage.getItem(key) ?? '');
    window.addEventListener('psms-backend-connected', refresh);
    return () => window.removeEventListener('psms-backend-connected', refresh);
  }, []);
  const connect = async () => {
    setLoading(true); setError('');
    try {
      const response = await fetch('/api/auth/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ username, password }), signal: AbortSignal.timeout(15_000) });
      const result = await response.json();
      if (!response.ok || typeof result.data?.accessToken !== 'string') throw new Error(result.message ?? '业务服务连接失败');
      sessionStorage.setItem(key, result.data.accessToken); setToken(result.data.accessToken); setPassword('');
      window.dispatchEvent(new Event('psms-backend-connected'));
    } catch (cause) { setError(cause instanceof Error ? cause.message : '业务服务暂不可用'); }
    finally { setLoading(false); }
  };
  const disconnect = () => { sessionStorage.removeItem(key); setToken(''); window.dispatchEvent(new Event('psms-backend-connected')); };
  return { token, username, password, error, loading, setUsername, setPassword, connect, disconnect };
}
export function BackendConnectionFields({ connection }: { connection: ReturnType<typeof useBackendConnection> }) {
  return connection.token ? <Space><Tag color="success">业务服务已登录</Tag><Button size="small" onClick={connection.disconnect}>退出连接</Button></Space> : <Space orientation="vertical" style={{ width: '100%' }}>
    <Input aria-label="业务服务账号" placeholder="业务服务账号" value={connection.username} onChange={(event) => connection.setUsername(event.target.value)} />
    <Input.Password aria-label="业务服务密码" placeholder="业务服务密码" value={connection.password} onChange={(event) => connection.setPassword(event.target.value)} />
    <Button loading={connection.loading} disabled={!connection.username || !connection.password} onClick={() => void connection.connect()}>登录业务服务</Button>
    {connection.error ? <Alert type="error" title={connection.error} /> : null}
  </Space>;
}
