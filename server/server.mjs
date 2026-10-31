import { createServer } from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { createNotionClient, GatewayError } from './notion.mjs';

export function createHandler({ appToken, notion }) {
  if (!appToken || appToken.length < 32) throw new Error('APP_ACCESS_TOKEN must contain at least 32 characters');
  const expected = Buffer.from('Bearer ' + appToken);
  return async (req, res) => {
    const respond = (status, body) => {
      res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
      res.end(JSON.stringify(body));
    };
    if (req.url === '/health' && req.method === 'GET') return respond(200, { ok: true });
    const supplied = Buffer.from(req.headers.authorization ?? '');
    if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected))
      return respond(401, { error: 'unauthorized' });
    if (req.method !== 'GET') return respond(405, { error: 'read_only' });
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname !== '/experiments') return respond(404, { error: 'not_found' });
    try { respond(200, await notion.list(url.searchParams.get('cursor') || undefined)); }
    catch (error) {
      if (error instanceof GatewayError) return respond(error.status, { error: error.code });
      respond(500, { error: 'internal_error' });
    }
  };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const notion = createNotionClient({
    token: process.env.NOTION_TOKEN, dataSourceId: process.env.NOTION_DATA_SOURCE_ID,
    dateProperty: process.env.NOTION_DATE_PROPERTY || '计划日期',
    statusProperty: process.env.NOTION_STATUS_PROPERTY || '状态'
  });
  const server = createServer(createHandler({ appToken: process.env.APP_ACCESS_TOKEN, notion }));
  server.requestTimeout = 20000;
  server.headersTimeout = 10000;
  server.listen(Number(process.env.PORT || 10000), '0.0.0.0');
}
