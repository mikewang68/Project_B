// Shared synthetic receipts for legacy UI fixtures. Fault behavior is tested in audit-ui.cjs.
module.exports = async function auditFixture(route) {
  const p = new URL(route.request().url()).pathname;
  let body;
  if (p === '/api/v1/audit/page-actions')
    body = { requestId: route.request().postDataJSON().requestId, state: 'RECORDED' };
  else if (/^\/api\/v1\/audit\/page-actions\/[^/]+\/result$/.test(p))
    body = { requestId: p.split('/')[5], state: 'RECORDED' };
  else if (p === '/api/v1/audit/settings')
    body = { canManage: false, configured: true, collectionEnabled: true, archivePaused: false, chainPaused: false, pendingCount: 0, failedCount: 0, oldestPendingAt: null };
  else return false;
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  return true;
};
