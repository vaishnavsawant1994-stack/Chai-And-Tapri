import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createPool, withTransaction } from '../src/db.mjs';
import { ApiError, SyncService } from '../src/service.mjs';
import { createApiServer } from '../src/http.mjs';

const connection = process.env.DATABASE_URL;
const adminConnection = process.env.TEST_ADMIN_DATABASE_URL ?? connection;
const projectionDelta = (stock = [{ productId:'test-stock', quantity:0 }]) => ({ kind:'projection_delta.v1',orders:[],stock,stockMovements:[],customerCredits:[],repayments:[],paymentEntries:[] });
const createAdminPool = () => createPool(adminConnection);
async function cleanupEnrollment(pool, enrollment) {
  if (!enrollment) return;
  await withTransaction(pool,async(c)=>{
    await c.query("SELECT set_config('app.current_shop',$1,true)",[enrollment.shopId]);
    await c.query('DELETE FROM sessions WHERE shop_id=$1',[enrollment.shopId]);
    await c.query('DELETE FROM devices WHERE shop_id=$1',[enrollment.shopId]);
    await c.query('DELETE FROM shop_memberships WHERE shop_id=$1',[enrollment.shopId]);
    await c.query('DELETE FROM sync_conflicts WHERE shop_id=$1',[enrollment.shopId]);
    await c.query('DELETE FROM sync_events WHERE shop_id=$1',[enrollment.shopId]);
    await c.query('DELETE FROM shops WHERE shop_id=$1',[enrollment.shopId]);
    await c.query('DELETE FROM businesses WHERE business_id=$1',[enrollment.businessId]);
    await c.query('DELETE FROM users WHERE user_id=$1',[enrollment.userId]);
  });
}
test('authenticated tenant sync journal preserves idempotency and exposes revision conflicts', { skip: !connection }, async () => {
  const pool=createPool(connection); const admin=createAdminPool(); const service=new SyncService(pool);
  const suffix=randomUUID(); const deviceId=randomUUID();
  let enrollment; let otherEnrollment;
  try {
    enrollment=await service.register({email:`owner-${suffix}@example.test`,password:'Synthetic-Only-Password-2026!',displayName:'Synthetic Owner',businessName:'Synthetic Business',shopName:'Synthetic Shop',deviceId,deviceLabel:'Jest Device'});
    const actor=await service.authenticate(enrollment.token,enrollment.shopId);
    assert.equal(actor.role,'owner');
    const event={type:'business.command.v1',schemaVersion:1,eventId:randomUUID(),commandId:`sale-${randomUUID()}`,businessId:enrollment.businessId,shopId:enrollment.shopId,deviceId,baseRevision:0,payload:projectionDelta([{productId:'chai',quantity:4}])};
    assert.deepEqual((await service.submitEvents(actor,[event])).results.map(x=>x.status),['accepted']);
    assert.deepEqual((await service.submitEvents(actor,[event])).results.map(x=>x.status),['duplicate']);
    const altered={...event,payload:projectionDelta([{productId:'chai',quantity:8}])};
    await assert.rejects(service.submitEvents(actor,[altered]),(e)=>e instanceof ApiError&&e.code==='event_idempotency_conflict');
    const stale={...event,eventId:randomUUID(),commandId:`refund-${randomUUID()}`};
    const conflict=await service.submitEvents(actor,[stale]);
    assert.equal(conflict.results[0].status,'conflict'); assert.equal(conflict.currentRevision,1);
    const otherDevice=randomUUID();
    otherEnrollment=await service.register({email:`other-${suffix}@example.test`,password:'Synthetic-Only-Password-2026!',displayName:'Other Owner',businessName:'Other Business',shopName:'Other Shop',deviceId:otherDevice,deviceLabel:'Other Device'});
    const otherActor=await service.authenticate(otherEnrollment.token,otherEnrollment.shopId);
    const otherEvent={type:'business.command.v1',schemaVersion:1,eventId:randomUUID(),commandId:`other-${randomUUID()}`,businessId:otherEnrollment.businessId,shopId:otherEnrollment.shopId,deviceId:otherDevice,baseRevision:0,payload:projectionDelta()};
    await service.submitEvents(otherActor,[otherEvent]);
    await assert.rejects(service.authenticate(enrollment.token,otherEnrollment.shopId),(e)=>e instanceof ApiError&&e.status===401);
    await assert.rejects(service.submitEvents(actor,[{...event,eventId:randomUUID(),commandId:'',baseRevision:1}]),(e)=>e instanceof ApiError&&e.code==='invalid_event_envelope');
    const invisible=await withTransaction(pool,async(c)=>{
      await c.query("SELECT set_config('app.current_shop',$1,true)",[enrollment.shopId]);
      return c.query('SELECT count(*)::integer AS count FROM sync_events WHERE shop_id=$1',[otherEnrollment.shopId]);
    });
    assert.equal(invisible.rows[0].count,0,'RLS must hide another shop even when a query requests its ID');
    await assert.rejects(withTransaction(pool,async(c)=>{
      await c.query("SELECT set_config('app.current_shop',$1,true)",[enrollment.shopId]);
      return c.query(`INSERT INTO sync_events(business_id,shop_id,server_revision,event_id,command_id,device_id,event_type,schema_version,base_revision,payload_hash,envelope)
        VALUES($1,$2,2,$3,$4,$5,'business.command.v1',1,1,$6,$7)`,[otherEnrollment.businessId,otherEnrollment.shopId,randomUUID(),randomUUID(),otherDevice,Buffer.alloc(32),otherEvent]);
    }),(e)=>e.code==='42501','RLS must reject cross-shop event writes');
    await assert.rejects(pool.query('DELETE FROM sync_events WHERE shop_id=$1',[enrollment.shopId]),(e)=>e.code==='42501','runtime role must not delete immutable event history');
    await service.logout(enrollment.token);
    await assert.rejects(service.authenticate(enrollment.token,enrollment.shopId),(e)=>e instanceof ApiError&&e.status===401);
  } finally {
    await cleanupEnrollment(admin,enrollment); await cleanupEnrollment(admin,otherEnrollment);
    await pool.end(); await admin.end();
  }
});

test('authentication rejects malformed identifiers and weak registration passwords before database writes', { skip: !connection }, async()=>{
  const pool=createPool(connection); const service=new SyncService(pool);
  try {
    await assert.rejects(service.register({email:'bad',password:'short',displayName:'A',businessName:'B',shopName:'S',deviceId:'x',deviceLabel:'D'}),(e)=>e instanceof ApiError&&e.status===400);
    await assert.rejects(service.login({email:'a@b.test',password:'password',shopId:'bad',deviceId:'bad'}),(e)=>e instanceof ApiError&&e.status===400);
  } finally { await pool.end(); }
});

test('repeated bad credentials persist a temporary login lock', { skip: !connection }, async()=>{
  const pool=createPool(connection); const admin=createAdminPool(); const service=new SyncService(pool); const suffix=randomUUID(); const deviceId=randomUUID(); let enrollment;
  try {
    enrollment=await service.register({email:`locked-${suffix}@example.test`,password:'Synthetic-Only-Password-2026!',displayName:'Synthetic Owner',businessName:'Synthetic Business',shopName:'Synthetic Shop',deviceId,deviceLabel:'Test Device'});
    for(let i=0;i<8;i++) await assert.rejects(service.login({email:`locked-${suffix}@example.test`,password:'incorrect-password',shopId:enrollment.shopId,deviceId}),(e)=>e instanceof ApiError&&e.status===401);
    await assert.rejects(service.login({email:`locked-${suffix}@example.test`,password:'Synthetic-Only-Password-2026!',shopId:enrollment.shopId,deviceId}),(e)=>e instanceof ApiError&&e.status===429);
  } finally {
    if(enrollment) { await cleanupEnrollment(admin,enrollment); await admin.query('DELETE FROM auth_failures WHERE email_key=$1',[`locked-${suffix}@example.test`]); }
    await pool.end(); await admin.end();
  }
});

test('HTTP API syncs a synthetic shop, projects owner activity, and revokes its bearer session', { skip: !connection }, async()=>{
  const pool=createPool(connection); const admin=createAdminPool(); const service=new SyncService(pool); const server=createApiServer(service); const deviceId=randomUUID(); let enrollment;
  await new Promise((resolve)=>server.listen(0,'127.0.0.1',resolve));
  const base=`http://127.0.0.1:${server.address().port}`;
  try {
    const health=await fetch(`${base}/health`); assert.equal(health.status,200);
    const registered=await fetch(`${base}/v1/register`,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({email:`http-${randomUUID()}@example.test`,password:'Synthetic-Only-Password-2026!',displayName:'HTTP Owner',businessName:'HTTP Business',shopName:'HTTP Shop',deviceId,deviceLabel:'HTTP Test'})});
    assert.equal(registered.status,201); enrollment=await registered.json();
    const expenseId=randomUUID();
    const event={type:'business.command.v1',schemaVersion:1,eventId:randomUUID(),commandId:`ui-sale-${randomUUID()}`,businessId:enrollment.businessId,shopId:enrollment.shopId,deviceId,baseRevision:0,payload:{...projectionDelta([{productId:'chai',quantity:4}]),expenses:[{
      expenseId,category:'utilities',amountPaise:25050,method:'cash',status:'staff_declared'
    }],orders:[{
      orderNumber:12,fulfillment:'GIVEN',payment:'STAFF_DECLARED',paidPaise:2000,creditPaise:0,refundedPaise:0,cashRefundedPaise:0,creditReversedPaise:0,
      salePosted:true,voided:false,revision:1,createdAtEpochMs:1000,
      lines:[{productId:'chai',quantity:2,deliveredQuantity:2,refundedQuantity:0,unitPricePaise:1000}]
    }],paymentEntries:[{entryId:randomUUID(),orderNumber:12,customerId:null,amountPaise:2000,method:'cash',status:'staff_declared',kind:'order_collection',relatedEntryId:null}]}};
    const sync=await fetch(`${base}/v1/sync/events`,{method:'POST',headers:{'content-type':'application/json',authorization:`Bearer ${enrollment.token}`},body:JSON.stringify({shopId:enrollment.shopId,events:[event]})});
    assert.equal(sync.status,200); assert.equal((await sync.json()).results[0].status,'accepted');
    const overview=await fetch(`${base}/v1/dashboard/overview?shopId=${enrollment.shopId}`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(overview.status,200); const summary=await overview.json();
    assert.equal(summary.shopId,enrollment.shopId); assert.equal(summary.recordedOrderCount,1);
    assert.equal(summary.netDeliveredItemValuePaise,2000); assert.equal(summary.cashCollectedPaise,2000);
    assert.equal(summary.trackedProductCount,1); assert.match(summary.evidence.cash,/Staff-declared/);
    const products=await fetch(`${base}/v1/dashboard/products?shopId=${enrollment.shopId}`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(products.status,200); const performance=await products.json();
    assert.equal(performance.shopId,enrollment.shopId); assert.equal(performance.productCount,1);
    assert.equal(performance.products[0].productId,'chai'); assert.equal(performance.products[0].netDeliveredUnits,2);
    assert.equal(performance.products[0].netDeliveredItemValuePaise,2000); assert.equal(performance.products[0].stockQuantity,4);
    assert.match(performance.evidence.itemValue,/not recognized profit/);
    const activity=await fetch(`${base}/v1/dashboard/activity?shopId=${enrollment.shopId}&days=7`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(activity.status,200); const daily=await activity.json();
    assert.equal(daily.shopId,enrollment.shopId); assert.equal(daily.serverRevision,1);
    assert.equal(daily.acceptedEventCount,1); assert.equal(daily.totals.staffDeclaredExpensesPaise,25050);
    assert.deepEqual(daily.expenseCategories,[{category:'utilities',count:1,amountPaise:25050}]);
    assert.equal(daily.daily.reduce((sum,day)=>sum+day.staffDeclaredExpensesPaise,0),25050);
    assert.match(daily.evidence.expenses,/not a profit calculation/);
    const duplicate=await fetch(`${base}/v1/sync/events`,{method:'POST',headers:{'content-type':'application/json',authorization:`Bearer ${enrollment.token}`},body:JSON.stringify({shopId:enrollment.shopId,events:[event]})});
    assert.equal(duplicate.status,200); assert.equal((await duplicate.json()).results[0].status,'duplicate');
    const afterRetry=await fetch(`${base}/v1/dashboard/activity?shopId=${enrollment.shopId}&days=7`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal((await afterRetry.json()).totals.staffDeclaredExpensesPaise,25050,'an exact event retry must not double-count expense activity');
    const wrongShop=await fetch(`${base}/v1/dashboard/overview?shopId=${randomUUID()}`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(wrongShop.status,401);
    const wrongActivityShop=await fetch(`${base}/v1/dashboard/activity?shopId=${randomUUID()}&days=7`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(wrongActivityShop.status,401);
    const logout=await fetch(`${base}/v1/logout`,{method:'POST',headers:{authorization:`Bearer ${enrollment.token}`}}); assert.equal(logout.status,200);
    const denied=await fetch(`${base}/v1/sync/events`,{method:'POST',headers:{'content-type':'application/json',authorization:`Bearer ${enrollment.token}`},body:JSON.stringify({shopId:enrollment.shopId,events:[{...event,eventId:randomUUID(),commandId:`retry-${randomUUID()}`,baseRevision:1}]})});
    assert.equal(denied.status,401);
    const deniedOverview=await fetch(`${base}/v1/dashboard/overview?shopId=${enrollment.shopId}`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(deniedOverview.status,401);
  } finally {
    await cleanupEnrollment(admin,enrollment);
    await new Promise((resolve)=>server.close(resolve)); await pool.end(); await admin.end();
  }
});
