import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createNotionClient } from './notion.mjs';
import { createHandler } from './server.mjs';
const id='00000000-0000-0000-0000-000000000001',pageId='00000000-0000-0000-0000-000000000002';
const config={token:'fixture-only',dataSourceId:id};
const page={id:pageId,parent:{data_source_id:id},properties:{Name:{type:'title',title:[{plain_text:'Synthetic experiment'}]}}};
const payload={operationId:'00000000-0000-0000-0000-000000000003',title:'Synthetic experiment',date:'2030-01-02',result:'Fixture observation'};
test('title-only source remains unscheduled and forwards pagination',async()=>{
 const client=createNotionClient(config,async(url,opts)=>{
  assert.ok(url.endsWith('data_sources/'+id+'/query'));assert.equal(JSON.parse(opts.body).start_cursor,'cursor-1');
  return Response.json({results:[page],has_more:true,next_cursor:'cursor-2'});
 });
 const data=await client.list('cursor-1');assert.equal(data.items[0].title,'Synthetic experiment');
 assert.equal(data.items[0].date,null);assert.equal(data.items[0].status,null);assert.equal(data.nextCursor,'cursor-2');
});
test('date/status mapping uses explicit fields',async()=>{
 const client=createNotionClient(config,async()=>Response.json({results:[{...page,properties:{...page.properties,
  '计划日期':{type:'date',date:{start:'2030-01-02'}},'状态':{type:'status',status:{name:'In progress'}}}}],has_more:false}));
 const data=await client.list();assert.equal(data.items[0].date,'2030-01-02');assert.equal(data.items[0].status,'In progress');
});
test('upstream credentials and response bodies are not exposed in errors',async()=>{
 for(const [status,code] of [[403,'notion_authorization_required'],[404,'notion_source_not_shared'],[429,'notion_rate_limited']]){
  const client=createNotionClient(config,async()=>new Response('sensitive fixture',{status}));
  await assert.rejects(client.list(),e=>e.code===code&&!e.message.includes('sensitive'));
 }
});
test('invalid responses do not silently become empty sources',async()=>{
 const client=createNotionClient(config,async()=>Response.json({}));
 await assert.rejects(client.list(),e=>e.code==='notion_invalid_response');
});
test('detail recursively reads nested blocks and returns image links',async()=>{
 const client=createNotionClient(config,async url=>{
  if(url.endsWith('pages/'+pageId))return Response.json(page);
  if(url.includes('/'+pageId+'/children'))return Response.json({results:[{id:'nested',type:'toggle',has_children:true,toggle:{rich_text:[{plain_text:'Design'}]}}],has_more:false});
  return Response.json({results:[{id:'p',type:'paragraph',paragraph:{rich_text:[{plain_text:'Synthetic nested detail'}]}},
   {id:'i',type:'image',image:{external:{url:'https://example.org/fixture.jpg'},caption:[]}}],has_more:false});
 });
 const result=await client.detail(pageId);assert.match(result.text,/Synthetic nested detail/);assert.equal(result.images.length,1);
});
test('arbitrary pages outside configured data source are rejected before block access',async()=>{
 let calls=0;const client=createNotionClient(config,async()=>{calls++;return Response.json({...page,parent:{data_source_id:'00000000-0000-0000-0000-000000000099'}});});
 await assert.rejects(client.detail(pageId),e=>e.code==='page_outside_configured_source');
 await assert.rejects(client.append(pageId,payload),e=>e.code==='page_outside_configured_source');assert.equal(calls,2);
});
test('concurrent retry with same operation appends only once and rejects changed payload',async()=>{
 const stored=[];let writes=0;
 const client=createNotionClient(config,async(url,opts)=>{
  if(url.includes('/pages/'))return Response.json(page);
  if(opts.method==='PATCH'){writes++;const block=JSON.parse(opts.body).children[0];stored.push({...block,id:'new-block'});return Response.json({results:[{id:'new-block'}]});}
  return Response.json({results:[...stored],has_more:false});
 });
 const result=await Promise.all([client.append(pageId,payload),client.append(pageId,payload)]);
 assert.equal(writes,1);assert.equal(result.filter(r=>r.duplicate).length,1);
 await assert.rejects(client.append(pageId,{...payload,result:'Changed fixture'}),e=>e.code==='operation_payload_conflict');assert.equal(writes,1);
});
test('image upload attaches returned file ID with multipart send',async()=>{
 let multipart=false,attached=false;
 const client=createNotionClient(config,async(url,opts)=>{
  if(url.includes('/pages/'))return Response.json(page);
  if(url.endsWith('/file_uploads'))return Response.json({id:'upload-fixture'});
  if(url.endsWith('/file_uploads/upload-fixture/send')){multipart=opts.body instanceof FormData;return Response.json({status:'uploaded'});}
  if(opts.method==='PATCH'){const block=JSON.parse(opts.body).children[0];attached=block.toggle.children.some(b=>b.type==='image'&&b.image.file_upload.id==='upload-fixture');return Response.json({results:[{id:'image-log'}]});}
  return Response.json({results:[],has_more:false});
 });
 await client.append(pageId,{...payload,images:[{mime:'image/jpeg',data:Buffer.from([255,216,255,217]).toString('base64'),caption:'Synthetic caption'}]});
 assert.equal(multipart,true);assert.equal(attached,true);
});
test('ambiguous append failures are marked uncertain, not silently retried',async()=>{
 let writes=0;const client=createNotionClient(config,async(url,opts)=>{
  if(url.includes('/pages/'))return Response.json(page);
  if(opts.method==='PATCH'){writes++;throw new Error('timeout with unknown server result');}
  return Response.json({results:[],has_more:false});
 });
 await assert.rejects(client.append(pageId,payload),e=>e.code==='write_uncertain');assert.equal(writes,1);
});
test('gateway authenticates reads/writes, limits body size and rejects malformed JSON',async t=>{
 let writes=0;const token='fixture-app-token-with-32-characters';
 const server=createServer(createHandler({appToken:token,notion:{list:async()=>({items:[]}),append:async()=>{writes++;return {synced:true};}}}));
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));t.after(()=>new Promise(resolve=>server.close(resolve)));
 const base='http://127.0.0.1:'+server.address().port;
 assert.equal((await fetch(base+'/health')).status,200);
 assert.equal((await fetch(base+'/experiments')).status,401);
 const headers={Authorization:'Bearer '+token,'Content-Type':'application/json'};
 assert.equal((await fetch(base+'/experiments',{headers})).status,200);
 assert.equal((await fetch(base+'/experiments/'+pageId+'/logs',{method:'POST',headers,body:'bad json'})).status,400);
 assert.equal(writes,0);
 assert.equal((await fetch(base+'/experiments/'+pageId+'/logs',{method:'POST',headers,body:JSON.stringify(payload)})).status,200);
 assert.equal(writes,1);
 assert.equal((await fetch(base+'/experiments/'+pageId,{method:'DELETE',headers})).status,405);
});
