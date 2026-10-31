export class GatewayError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}
export function createNotionClient(config, fetcher = fetch) {
  const { token, dataSourceId, dateProperty = '计划日期', statusProperty = '状态' } = config;
  const validId = /^[a-f0-9]{32}$/i.test(dataSourceId ?? '') || /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(dataSourceId ?? '');
  if (!token || !validId) throw new Error('Missing or invalid Notion configuration');
  async function request(path, body) {
    let response;
    try {
      response = await fetcher('https://api.notion.com/v1/' + path, {
        method: body ? 'POST' : 'GET',
        headers: { Authorization: 'Bearer ' + token, 'Notion-Version': '2025-09-03', 'Content-Type': 'application/json' },
        body: body ? JSON.stringify(body) : undefined,
        signal: AbortSignal.timeout(15000),
        redirect: 'error'
      });
    } catch { throw new GatewayError(502, 'notion_unreachable'); }
    if (!response.ok) {
      const code = response.status === 429 ? 'notion_rate_limited' :
        response.status === 401 || response.status === 403 ? 'notion_authorization_required' :
        response.status === 404 ? 'notion_source_not_shared' : 'notion_request_failed';
      throw new GatewayError(response.status === 429 ? 503 : 502, code);
    }
    try { return await response.json(); } catch { throw new GatewayError(502, 'notion_invalid_response'); }
  }
  return {
    async list(cursor) {
      if (cursor && (typeof cursor !== 'string' || cursor.length > 512)) throw new GatewayError(400, 'invalid_cursor');
      const body = { page_size: 100 };
      if (cursor) body.start_cursor = cursor;
      const data = await request('data_sources/' + dataSourceId + '/query', body);
      if (!Array.isArray(data.results)) throw new GatewayError(502, 'notion_invalid_response');
      return {
        items: data.results.map(page => {
          const props = page.properties ?? {};
          const title = Object.values(props).find(p => p.type === 'title')?.title ?? [];
          const date = props[dateProperty];
          const status = props[statusProperty];
          return {
            id: page.id,
            title: title.map(t => t.plain_text ?? t.text?.content ?? '').join(''),
            date: date?.type === 'date' ? date.date?.start ?? null : null,
            status: status?.type === 'status' ? status.status?.name ?? null :
              status?.type === 'select' ? status.select?.name ?? null : null,
            url: page.url ?? null,
            lastEditedAt: page.last_edited_time ?? null
          };
        }),
        nextCursor: data.has_more ? data.next_cursor : null,
        readOnly: true
      };
    }
  };
}
