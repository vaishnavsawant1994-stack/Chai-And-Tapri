import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { ApiError } from './service.mjs';

const json = (res,status,body) => { res.writeHead(status,{'content-type':'application/json; charset=utf-8','cache-control':'no-store'}); res.end(JSON.stringify(body)); };
const hasExactKeys = (value, keys) => value !== null && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === keys.length && keys.every((key) => Object.hasOwn(value, key));
const ownerAssets = new Map([
  ['/owner/', ['../../owner-web/index.html', 'text/html; charset=utf-8']],
  ['/owner/index.html', ['../../owner-web/index.html', 'text/html; charset=utf-8']],
  ['/owner/app.js', ['../../owner-web/app.js', 'text/javascript; charset=utf-8']],
  ['/owner/report-export.mjs', ['../../owner-web/report-export.mjs', 'text/javascript; charset=utf-8']],
  ['/owner/quantity-format.mjs', ['../../owner-web/quantity-format.mjs', 'text/javascript; charset=utf-8']],
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
      if(req.method==='GET'&&url.pathname==='/favicon.ico') { res.writeHead(204,{'cache-control':'no-store'}); return res.end(); }
      if(req.method==='GET'&&url.pathname==='/health') return json(res,200,{status:'ok',service:'sync-journal-prototype'});
      if(req.method==='GET'&&url.pathname==='/owner') { res.writeHead(308,{location:'/owner/', 'cache-control':'no-store'}); return res.end(); }
      if(req.method==='GET'&&ownerAssets.has(url.pathname)) return await serveOwnerAsset(url.pathname,res);
      if(req.method==='POST'&&url.pathname==='/v1/register') return json(res,201,await service.register(await readBody(req)));
      if(req.method==='POST'&&url.pathname==='/v1/login') return json(res,200,await service.login(await readBody(req)));
      if(req.method==='POST'&&url.pathname==='/v1/staff/accept-invitation') {
        const body=await readBody(req);
        if(!hasExactKeys(body,['invitationToken','displayName','password','deviceId','deviceLabel'])) throw new ApiError(400,'invalid_staff_invitation');
        return json(res,201,await service.acceptStaffInvitation(body));
      }
      const token=(req.headers.authorization??'').replace(/^Bearer\s+/i,'');
      if(req.method==='POST'&&url.pathname==='/v1/logout') { await service.logout(token); return json(res,200,{status:'revoked'}); }
      if(req.method==='POST'&&url.pathname==='/v1/sync/events') {
        const body=await readBody(req); const actor=await service.authenticate(token,body.shopId);
        const result=await service.submitEvents(actor,body.events);
        const conflict=result.results.some(x=>x.status==='conflict'); return json(res,conflict?409:200,result);
      }
      const catalogueMatch=url.pathname.match(/^\/v1\/sync\/catalogue\/([0-9a-f-]{36})$/i);
      if(req.method==='GET'&&catalogueMatch) {
        const actor=await service.authenticate(token,catalogueMatch[1]);
        return json(res,200,await service.shopCatalogue(actor));
      }
      if(url.pathname==='/v1/staff/members'&&req.method==='GET') {
        const shopId=url.searchParams.get('shopId');
        const actor=await service.authenticate(token,shopId);
        return json(res,200,await service.shopStaffMembers(actor));
      }
      if(url.pathname==='/v1/staff/invitations'&&req.method==='GET') {
        const shopId=url.searchParams.get('shopId');
        const actor=await service.authenticate(token,shopId);
        return json(res,200,await service.shopStaffInvitations(actor));
      }
      if(url.pathname==='/v1/staff/audit'&&req.method==='GET') {
        const shopId=url.searchParams.get('shopId');
        const actor=await service.authenticate(token,shopId);
        return json(res,200,await service.shopStaffAudit(actor));
      }
      if(url.pathname==='/v1/staff/invitations'&&req.method==='POST') {
        const body=await readBody(req);
        if(!hasExactKeys(body,['shopId','email','role'])) throw new ApiError(400,'invalid_staff_invitation');
        const actor=await service.authenticate(token,body.shopId);
        return json(res,201,await service.createStaffInvitation(actor,body));
      }
      const invitationMatch=url.pathname.match(/^\/v1\/staff\/invitations\/([0-9a-f-]+)$/i);
      if(invitationMatch&&req.method==='DELETE') {
        const actor=await service.authenticate(token,url.searchParams.get('shopId'));
        return json(res,200,await service.revokeStaffInvitation(actor,invitationMatch[1]));
      }
      const memberMatch=url.pathname.match(/^\/v1\/staff\/members\/([0-9a-f-]+)$/i);
      if(memberMatch&&req.method==='PATCH') {
        const body=await readBody(req);
        if(!hasExactKeys(body,['shopId','role'])) throw new ApiError(400,'invalid_staff_member_update');
        const actor=await service.authenticate(token,body.shopId);
        return json(res,200,await service.setStaffMemberRole(actor,memberMatch[1],body.role));
      }
      if(memberMatch&&req.method==='DELETE') {
        const actor=await service.authenticate(token,url.searchParams.get('shopId'));
        return json(res,200,await service.removeStaffMember(actor,memberMatch[1]));
      }
      if(req.method==='GET'&&url.pathname==='/v1/dashboard/overview') {
        const shopId=url.searchParams.get('shopId');
        const actor=await service.authenticate(token,shopId);
        return json(res,200,await service.shopOverview(actor));
      }
      if(req.method==='GET'&&url.pathname==='/v1/dashboard/insights') {
        const shopId=url.searchParams.get('shopId');
        const actor=await service.authenticate(token,shopId);
        return json(res,200,await service.shopOwnerInsights(actor));
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
      if(req.method==='GET'&&url.pathname==='/v1/dashboard/suppliers') {
        const shopId=url.searchParams.get('shopId');
        const actor=await service.authenticate(token,shopId);
        return json(res,200,await service.shopSupplierBalances(actor));
      }
      if(req.method==='GET'&&url.pathname==='/v1/dashboard/customers') {
        const shopId=url.searchParams.get('shopId');
        const actor=await service.authenticate(token,shopId);
        return json(res,200,await service.shopCustomerBalances(actor));
      }
      if(req.method==='GET'&&url.pathname==='/v1/dashboard/activity') {
        const shopId=url.searchParams.get('shopId');
        const rawDays=url.searchParams.get('days')??'30';
        const days=Number(rawDays);
        if(!Number.isInteger(days)||![7,30,90].includes(days)) throw new ApiError(400,'invalid_activity_range');
        const actor=await service.authenticate(token,shopId);
        return json(res,200,await service.shopDailySalesActivity(actor,days));
      }
      if(req.method==='GET'&&url.pathname==='/v1/dashboard/cashflow') {
        const shopId=url.searchParams.get('shopId');
        const rawDays=url.searchParams.get('days')??'30';
        const days=Number(rawDays);
        if(!Number.isInteger(days)||![7,30,90].includes(days)) throw new ApiError(400,'invalid_activity_range');
        const actor=await service.authenticate(token,shopId);
        return json(res,200,await service.shopRecordedMoneyMovements(actor,days));
      }
      if(req.method==='GET'&&url.pathname==='/v1/dashboard/profit-readiness') {
        const shopId=url.searchParams.get('shopId');
        const rawDays=url.searchParams.get('days')??'30';
        const days=Number(rawDays);
        if(!Number.isInteger(days)||![7,30,90].includes(days)) throw new ApiError(400,'invalid_activity_range');
        const actor=await service.authenticate(token,shopId);
        return json(res,200,await service.shopProfitReadiness(actor,days));
      }
      if(req.method==='GET'&&url.pathname==='/v1/dashboard/audit') {
        const shopId=url.searchParams.get('shopId');
        const limit=Number(url.searchParams.get('limit')??'100');
        const rawBeforeRevision=url.searchParams.get('beforeRevision');
        const beforeRevision=rawBeforeRevision===null?null:Number(rawBeforeRevision);
        if(!Number.isInteger(limit)||limit<1||limit>100) throw new ApiError(400,'invalid_audit_limit');
        if(beforeRevision!==null&&(!Number.isSafeInteger(beforeRevision)||beforeRevision<1)) throw new ApiError(400,'invalid_audit_cursor');
        const actor=await service.authenticate(token,shopId);
        return json(res,200,await service.shopAuditHistory(actor,limit,beforeRevision));
      }
      if(req.method==='GET'&&url.pathname==='/v1/dashboard/conflicts') {
        const shopId=url.searchParams.get('shopId');
        const limit=Number(url.searchParams.get('limit')??'100');
        if(!Number.isInteger(limit)||limit<1||limit>100) throw new ApiError(400,'invalid_conflict_limit');
        const rawBeforeCreatedAt=url.searchParams.get('beforeCreatedAt');
        const beforeConflictId=url.searchParams.get('beforeConflictId');
        if((rawBeforeCreatedAt===null)!==(beforeConflictId===null)) throw new ApiError(400,'invalid_conflict_cursor');
        const before=rawBeforeCreatedAt===null?null:{createdAt:rawBeforeCreatedAt,conflictId:beforeConflictId};
        const actor=await service.authenticate(token,shopId);
        return json(res,200,await service.shopSyncConflicts(actor,limit,before));
      }
      if(req.method==='GET'&&url.pathname==='/v1/dashboard/shifts') {
        const shopId=url.searchParams.get('shopId');
        const actor=await service.authenticate(token,shopId);
        return json(res,200,await service.shopStaffShifts(actor));
      }
      return json(res,404,{error:'not_found'});
    } catch(error) {
      const status=error instanceof ApiError?error.status:500;
      if(status===500) console.error('request_failed',error?.constructor?.name??'Error');
      return json(res,status,{error:error instanceof ApiError?error.code:'internal_error'});
    }
  });
}
