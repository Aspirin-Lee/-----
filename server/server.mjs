import { createServer } from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { createNotionClient, GatewayError } from './notion.mjs';
export function createHandler({ appToken, notion }) {
  if (!appToken || appToken.length < 32) throw new Error('APP_ACCESS_TOKEN must contain at least 32 characters');
  const expected = Buffer.from('Bearer ' + appToken);
  let active = 0;
  return async (req,res) => {
    const respond = (status,body) => { res.writeHead(status, {'Content-Type':'application/json; charset=utf-8',
      'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});res.end(JSON.stringify(body)); };
    if(req.url === '/health' && req.method === 'GET') return respond(200,{ok:true,version:'0.2.0'});
    const supplied = Buffer.from(req.headers.authorization ?? '');
    if(supplied.length !== expected.length || !timingSafeEqual(supplied,expected)) return respond(401,{error:'unauthorized'});
    if(active >= 4) return respond(503,{error:'gateway_busy'});
    active++;
    try {
      const url = new URL(req.url,'http://localhost');
      if(url.pathname === '/experiments' && req.method === 'GET')
        return respond(200,await notion.list(url.searchParams.get('cursor') || undefined));
      const match = /^\/experiments\/([a-f0-9-]+)(\/logs)?$/i.exec(url.pathname);
      if(!match) return respond(404,{error:'not_found'});
      if(!match[2] && req.method === 'GET') return respond(200,await notion.detail(match[1]));
      if(match[2] && req.method === 'POST') {
        if(!String(req.headers['content-type']).startsWith('application/json'))
          return respond(415,{error:'json_required'});
        const size=Number(req.headers['content-length'] ?? 0);
        if(size > 10*1024*1024) return respond(413,{error:'payload_too_large'});
        let total=0; const chunks=[];
        for await(const chunk of req) {
          total+=chunk.length;
          if(total > 10*1024*1024) { respond(413,{error:'payload_too_large'});req.destroy();return; }
          chunks.push(chunk);
        }
        let body;try{body=JSON.parse(Buffer.concat(chunks).toString('utf8'));}
        catch{return respond(400,{error:'invalid_json'});}
        return respond(200,await notion.append(match[1],body));
      }
      respond(405,{error:'method_not_allowed'});
    } catch(error) {
      if(error instanceof GatewayError)return respond(error.status,{error:error.code});
      if(!res.headersSent)respond(500,{error:'internal_error'});
    } finally { active--; }
  };
}
if(process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const notion=createNotionClient({token:process.env.NOTION_TOKEN,dataSourceId:process.env.NOTION_DATA_SOURCE_ID,
    dateProperty:process.env.NOTION_DATE_PROPERTY || '计划日期',statusProperty:process.env.NOTION_STATUS_PROPERTY || '状态'});
  const server=createServer(createHandler({appToken:process.env.APP_ACCESS_TOKEN,notion}));
  server.requestTimeout=120000;server.headersTimeout=15000;
  server.listen(Number(process.env.PORT || 10000),'0.0.0.0');
}
