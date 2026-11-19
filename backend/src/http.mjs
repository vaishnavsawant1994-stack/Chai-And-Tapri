import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { ApiError } from './service.mjs';

const json = (res,status,body) => { res.writeHead(status,{'content-type':'application/json; charset=utf-8','cache-control':'no-store'}); res.end(JSON.stringify(body)); };
const ownerAssets = new Map([
  ['/owner/', ['../../owner-web/index.html', 'text/html; charset=utf-8']],
  ['/owner/index.html', ['../../owner-web/index.html', 'text/html; charset=utf-8']],
  ['/owner/app.js', ['../../owner-web/app.js', 'text/javascript; charset=utf-8']],
  ['/owner/styles.css', ['../../owner-web/styles.css', 'text/css; charset=utf-8']]
]);
async function serveOwnerAsset(path,res) {
  const asset=ownerAssets.get(path);
  if(!asset) return false;
  const body=await readFile(new URL(asset[0],import.meta.url));
  res.writeHead(200,{
    'content-type':asset[1], 'cache-control':'no-store', 'x-content-type-options':'nosniff',
    'referrer-policy':'no-referrer',
    'content-security-policy':"default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
  });
  res.end(body);
  return true;
}
async function readBody(req) {
  const chunks=[]; let size=0;
  for await (const chunk of req) { size+=chunk.length; if(size>256*1024) throw new ApiError(413,'request_too_large'); chunks.push(chunk); }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw new ApiError(400,'invalid_json'); }
}

export function createApiServer(service) {
  return createServer(async(req,res)=>{
    try {
      const url=new URL(req.url,'http://localhost');
      if(req.method==='GET'&&url.pathname==='/health') return json(res,200,{status:'ok',service:'sync-journal-prototype'});
      if(req.method==='GET'&&url.pathname==='/owner') { res.writeHead(308,{location:'/owner/', 'cache-control':'no-store'}); return res.end(); }
      if(req.method==='GET'&&ownerAssets.has(url.pathname)) return await serveOwnerAsset(url.pathname,res);
      if(req.method==='POST'&&url.pathname==='/v1/register') return json(res,201,await service.register(await readBody(req)));
      if(req.method==='POST'&&url.pathname==='/v1/login') return json(res,200,await service.login(await readBody(req)));
      const token=(req.headers.authorization??'').replace(/^Bearer\s+/i,'');
      if(req.method==='POST'&&url.pathname==='/v1/logout') { await service.logout(token); return json(res,200,{status:'revoked'}); }
      if(req.method==='POST'&&url.pathname==='/v1/sync/events') {
        const body=await readBody(req); const actor=await service.authenticate(token,body.shopId);
        const result=await service.submitEvents(actor,body.events);
        const conflict=result.results.some(x=>x.status==='conflict'); return json(res,conflict?409:200,result);
      }
      if(req.method==='GET'&&url.pathname==='/v1/dashboard/overview') {
        const shopId=url.searchParams.get('shopId');
        const actor=await service.authenticate(token,shopId);
        return json(res,200,await service.shopOverview(actor));
      }
      if(req.method==='GET'&&url.pathname==='/v1/dashboard/products') {
        const shopId=url.searchParams.get('shopId');
        const actor=await service.authenticate(token,shopId);
        return json(res,200,await service.shopProductPerformance(actor));
      }
      if(req.method==='GET'&&url.pathname==='/v1/dashboard/inventory') {
        const shopId=url.searchParams.get('shopId');
        const actor=await service.authenticate(token,shopId);
        return json(res,200,await service.shopInventorySnapshot(actor));
      }
      if(req.method==='GET'&&url.pathname==='/v1/dashboard/activity') {
        const shopId=url.searchParams.get('shopId');
        const rawDays=url.searchParams.get('days')??'30';
        const days=Number(rawDays);
        if(!Number.isInteger(days)||![7,30,90].includes(days)) throw new ApiError(400,'invalid_activity_range');
        const actor=await service.authenticate(token,shopId);
        return json(res,200,await service.shopDailySalesActivity(actor,days));
      }
      return json(res,404,{error:'not_found'});
    } catch(error) {
      const status=error instanceof ApiError?error.status:500;
      if(status===500) console.error('request_failed',error?.constructor?.name??'Error');
      return json(res,status,{error:error instanceof ApiError?error.code:'internal_error'});
    }
  });
}
