import { createHash } from 'node:crypto';
export class GatewayError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}
const cleanId = value => String(value ?? '').replaceAll('-', '').toLowerCase();
const validId = value => /^[a-f0-9]{32}$/i.test(cleanId(value));
const plain = value => (value ?? []).map(t => t.plain_text ?? t.text?.content ?? '').join('');
const rich = text => [{ type: 'text', text: { content: text } }];
const para = text => ({ object: 'block', type: 'paragraph', paragraph: { rich_text: rich(text) } });
export function createNotionClient(config, fetcher = fetch) {
  const { token, dataSourceId, dateProperty = '计划日期', statusProperty = '状态' } = config;
  if (!token || !validId(dataSourceId)) throw new Error('Missing or invalid Notion configuration');
  const locks = new Map();
  async function request(path, body, method = body ? 'POST' : 'GET', multipart = false) {
    let response;
    try {
      response = await fetcher('https://api.notion.com/v1/' + path, {
        method, headers: { Authorization: 'Bearer ' + token, 'Notion-Version': '2025-09-03',
          ...(!multipart ? { 'Content-Type': 'application/json' } : {}) },
        body: body ? multipart ? body : JSON.stringify(body) : undefined,
        signal: AbortSignal.timeout(20000), redirect: 'error'
      });
    } catch { throw new GatewayError(502, method === 'PATCH' ? 'write_uncertain' : 'notion_unreachable'); }
    if (!response.ok) {
      const code = response.status === 429 ? 'notion_rate_limited' :
        response.status === 401 || response.status === 403 ? 'notion_authorization_required' :
        response.status === 404 ? 'notion_source_not_shared' :
        method === 'PATCH' && response.status >= 500 ? 'write_uncertain' : 'notion_request_failed';
      throw new GatewayError(response.status === 429 ? 503 : 502, code);
    }
    try { return await response.json(); }
    catch { throw new GatewayError(502, method === 'PATCH' ? 'write_uncertain' : 'notion_invalid_response'); }
  }
  async function allowed(pageId) {
    if (!validId(pageId)) throw new GatewayError(400, 'invalid_page_id');
    const page = await request('pages/' + pageId);
    if (cleanId(page.parent?.data_source_id) !== cleanId(dataSourceId))
      throw new GatewayError(403, 'page_outside_configured_source');
    return page;
  }
  async function children(id) {
    const result = []; let cursor;
    for (let i = 0; i < 20; i++) {
      const data = await request('blocks/' + id + '/children?page_size=100' +
        (cursor ? '&start_cursor=' + encodeURIComponent(cursor) : ''));
      if (!Array.isArray(data.results)) throw new GatewayError(502, 'notion_invalid_response');
      result.push(...data.results);
      if (!data.has_more) return result;
      cursor = data.next_cursor;
      if (!cursor) throw new GatewayError(502, 'notion_invalid_response');
    }
    throw new GatewayError(413, 'page_too_large');
  }
  function mapPage(page) {
    const props = page.properties ?? {};
    const title = Object.values(props).find(p => p.type === 'title')?.title ?? [];
    const date = props[dateProperty], status = props[statusProperty];
    return { id: page.id, title: plain(title),
      date: date?.type === 'date' ? date.date?.start ?? null : null,
      status: status?.type === 'status' ? status.status?.name ?? null :
        status?.type === 'select' ? status.select?.name ?? null : null,
      url: page.url ?? null, lastEditedAt: page.last_edited_time ?? null };
  }
  async function serialized(id, action) {
    const previous = locks.get(id) ?? Promise.resolve();
    const work = previous.catch(() => {}).then(action);
    locks.set(id, work);
    try { return await work; } finally { if (locks.get(id) === work) locks.delete(id); }
  }
  return {
    async list(cursor) {
      if (cursor && (typeof cursor !== 'string' || cursor.length > 512)) throw new GatewayError(400, 'invalid_cursor');
      const data = await request('data_sources/' + dataSourceId + '/query',
        { page_size: 100, ...(cursor ? { start_cursor: cursor } : {}) });
      if (!Array.isArray(data.results)) throw new GatewayError(502, 'notion_invalid_response');
      return { items: data.results.map(mapPage), nextCursor: data.has_more ? data.next_cursor : null, readOnly: false };
    },
    async detail(id) {
      const page = await allowed(id), lines = [], images = [];
      let count = 0, truncated = false, unsupported = 0;
      async function walk(parent, depth) {
        if (depth > 6) { truncated = true; return; }
        for (const block of await children(parent)) {
          if (++count > 500) { truncated = true; return; }
          const payload = block[block.type] ?? {};
          const text = plain(payload.rich_text ?? payload.caption);
          if (text) lines.push('  '.repeat(depth) + (block.type === 'to_do' ? payload.checked ? '[x] ' : '[ ] ' : '') + text);
          if (block.type === 'image') {
            const url = payload.file?.url ?? payload.external?.url;
            if (url) images.push({ url, caption: plain(payload.caption) });
          }
          if (block.type === 'child_page') { lines.push('[子页面] ' + (payload.title ?? '')); unsupported++; }
          if (block.type === 'child_database') { lines.push('[嵌入数据库]'); unsupported++; }
          if (!text && !['image', 'divider', 'child_page', 'child_database'].includes(block.type) && !block.has_children)
            unsupported++;
          if (block.has_children && !['child_page', 'child_database'].includes(block.type)) await walk(block.id, depth + 1);
          if (count > 500) return;
        }
      }
      await walk(id, 0);
      return { ...mapPage(page), text: lines.join('\n'), images, truncated, unsupported };
    },
    async append(id, payload) {
      await allowed(id);
      if (!payload || !/^[a-f0-9-]{36}$/i.test(payload.operationId ?? ''))
        throw new GatewayError(400, 'invalid_operation_id');
      const fields = ['title','date','design','result','sample','project','anomaly','imageNotes'];
      for (const key of fields)
        if (payload[key] != null && (typeof payload[key] !== 'string' || payload[key].length > 12000))
          throw new GatewayError(400, 'invalid_' + key);
      const images = payload.images ?? [];
      if (!Array.isArray(images) || images.length > 3) throw new GatewayError(400, 'too_many_images');
      for (const image of images) {
        if (image.mime !== 'image/jpeg' || typeof image.data !== 'string' ||
          !/^[A-Za-z0-9+/]*={0,2}$/.test(image.data) || image.data.length > 2800000)
          throw new GatewayError(400, 'invalid_image');
        const bytes = Buffer.from(image.data, 'base64');
        if (bytes.length < 3 || bytes[0] !== 255 || bytes[1] !== 216 || bytes[2] !== 255)
          throw new GatewayError(400, 'invalid_jpeg');
        if (image.caption != null && (typeof image.caption !== 'string' || image.caption.length > 1000))
          throw new GatewayError(400, 'invalid_caption');
      }
      const digest = createHash('sha256').update(JSON.stringify(payload)).digest('hex').slice(0,24);
      const marker = 'LabDay:' + payload.operationId;
      return serialized(cleanId(id), async () => {
        const existing = (await children(id)).find(b => plain(b[b.type]?.rich_text).includes(marker));
        if (existing) {
          if (!plain(existing[existing.type]?.rich_text).includes(digest))
            throw new GatewayError(409, 'operation_payload_conflict');
          return { synced: true, duplicate: true, blockId: existing.id };
        }
        const blocks = [];
        const add = (label, value) => {
          const text = label + ': ' + (value || '未填写');
          for (let i=0;i<text.length;i+=1800) blocks.push(para(text.slice(i,i+1800)));
        };
        for (const [key,label] of [['title','实验'],['date','计划日期'],['project','项目'],['sample','样本/分组'],
          ['design','设计/操作'],['result','结果/观察'],['anomaly','异常/下次改进'],['imageNotes','图片说明']]) add(label,payload[key]);
        add('完成状态',payload.done ? '已完成' : '未完成');
        if (blocks.length + images.length > 95) throw new GatewayError(413,'log_too_large');
        for (const image of images) {
          const upload = await request('file_uploads', { mode: 'single_part', filename: 'labday.jpg', content_type: 'image/jpeg' });
          const form = new FormData();
          form.set('file', new Blob([Buffer.from(image.data,'base64')], { type: 'image/jpeg' }), 'labday.jpg');
          const sent = await request('file_uploads/' + upload.id + '/send',form,'POST',true);
          if (sent.status !== 'uploaded') throw new GatewayError(502,'image_upload_incomplete');
          blocks.push({ object:'block',type:'image',image:{type:'file_upload',file_upload:{id:upload.id},
            caption:rich(image.caption || '实验图片（手机压缩副本，原图保存在本机备份）')} });
        }
        const block = { object:'block',type:'toggle',toggle:{
          rich_text:rich('实验记录 '+(payload.date || '')+' · '+marker+' · '+digest),children:blocks } };
        const response = await request('blocks/' + id + '/children',{children:[block]},'PATCH');
        return { synced:true,duplicate:false,blockId:response.results?.[0]?.id ?? null };
      });
    }
  };
}
