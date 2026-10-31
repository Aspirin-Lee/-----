import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createNotionClient } from './notion.mjs';
import { createHandler } from './server.mjs';

const id = '00000000-0000-0000-0000-000000000001';
const config = { token: 'fixture-only', dataSourceId: id };
const page = { id: 'fixture-page', properties: { Name: { type: 'title',
  title: [{ plain_text: 'Synthetic experiment' }] } } };
test('title-only source remains unscheduled and pagination is forwarded', async () => {
  const client = createNotionClient(config, async (url, options) => {
    assert.equal(url, 'https://api.notion.com/v1/data_sources/' + id + '/query');
    assert.equal(JSON.parse(options.body).start_cursor, 'cursor-1');
    assert.equal(options.headers['Notion-Version'], '2025-09-03');
    return Response.json({ results: [page], has_more: true, next_cursor: 'cursor-2' });
  });
  const data = await client.list('cursor-1');
  assert.equal(data.items[0].title, 'Synthetic experiment');
  assert.equal(data.items[0].date, null);
  assert.equal(data.items[0].status, null);
  assert.equal(data.nextCursor, 'cursor-2');
});
test('explicit date and status fields are mapped without guessing', async () => {
  const client = createNotionClient(config, async () => Response.json({
    results: [{ ...page, properties: { ...page.properties,
      '计划日期': { type: 'date', date: { start: '2030-01-02' } },
      '状态': { type: 'status', status: { name: 'In progress' } }
    } }], has_more: false
  }));
  const data = await client.list();
  assert.equal(data.items[0].date, '2030-01-02');
  assert.equal(data.items[0].status, 'In progress');
  assert.equal(data.nextCursor, null);
});
test('authorization and throttling errors do not expose upstream response', async () => {
  for (const [status, code] of [[403, 'notion_authorization_required'], [404, 'notion_source_not_shared'], [429, 'notion_rate_limited']]) {
    const client = createNotionClient(config, async () => new Response('sensitive fixture', { status }));
    await assert.rejects(client.list(), error => error.code === code && !error.message.includes('sensitive'));
  }
});
test('invalid response fails rather than silently returning an empty source', async () => {
  const client = createNotionClient(config, async () => Response.json({}));
  await assert.rejects(client.list(), error => error.code === 'notion_invalid_response');
});
test('gateway requires authentication and rejects all writes', async t => {
  let calls = 0;
  const token = 'fixture-app-token-with-32-characters';
  const server = createServer(createHandler({ appToken: token, notion: { list: async () => { calls++; return { items: [] }; } } }));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = 'http://127.0.0.1:' + server.address().port;
  assert.equal((await fetch(base + '/health')).status, 200);
  assert.equal((await fetch(base + '/experiments')).status, 401);
  assert.equal(calls, 0);
  const headers = { Authorization: 'Bearer ' + token };
  assert.equal((await fetch(base + '/experiments', { headers })).status, 200);
  assert.equal(calls, 1);
  assert.equal((await fetch(base + '/experiments', { headers, method: 'POST' })).status, 405);
  assert.equal(calls, 1);
});
