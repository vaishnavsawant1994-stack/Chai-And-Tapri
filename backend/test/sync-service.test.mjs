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
    await c.query('DELETE FROM sessions WHERE business_id=$1',[enrollment.businessId]);
    await c.query('DELETE FROM sync_conflicts WHERE business_id=$1',[enrollment.businessId]);
    await c.query('DELETE FROM sync_events WHERE business_id=$1',[enrollment.businessId]);
    await c.query('DELETE FROM shop_staff_audit WHERE business_id=$1',[enrollment.businessId]);
    await c.query('DELETE FROM staff_invitations WHERE business_id=$1',[enrollment.businessId]);
    await c.query('DELETE FROM business_branch_audit WHERE business_id=$1',[enrollment.businessId]);
    await c.query('DELETE FROM shop_memberships WHERE business_id=$1',[enrollment.businessId]);
    await c.query('DELETE FROM devices WHERE business_id=$1',[enrollment.businessId]);
    await c.query('DELETE FROM shops WHERE business_id=$1',[enrollment.businessId]);
    await c.query('DELETE FROM business_memberships WHERE business_id=$1',[enrollment.businessId]);
    await c.query('DELETE FROM businesses WHERE business_id=$1',[enrollment.businessId]);
    await c.query('DELETE FROM users WHERE user_id=$1',[enrollment.userId]);
  });
}
test('authenticated tenant sync journal preserves idempotency and exposes revision conflicts', { skip: !connection }, async () => {
  const pool=createPool(connection); const admin=createAdminPool(); const service=new SyncService(pool);
  const suffix=randomUUID(); const deviceId=randomUUID();
  let enrollment; let otherEnrollment; let server;
  try {
    enrollment=await service.register({email:`owner-${suffix}@example.test`,password:'Synthetic-Only-Password-2026!',displayName:'Synthetic Owner',businessName:'Synthetic Business',shopName:'Synthetic Shop',deviceId,deviceLabel:'Jest Device'});
    const actor=await service.authenticate(enrollment.token,enrollment.shopId);
    assert.equal(actor.role,'owner');
    const event={type:'business.command.v1',schemaVersion:1,eventId:randomUUID(),commandId:`sale-${randomUUID()}`,businessId:enrollment.businessId,shopId:enrollment.shopId,deviceId,baseRevision:0,payload:projectionDelta([{productId:'chai',quantity:4}])};
    assert.deepEqual((await service.submitEvents(actor,[event])).results.map(x=>x.status),['accepted']);
    assert.deepEqual((await service.submitEvents(actor,[event])).results.map(x=>x.status),['duplicate']);
    const audit=await service.shopAuditHistory(actor,10);
    assert.equal(audit.returnedEventCount,1);
    assert.equal(audit.events[0].serverRevision,1);
    assert.equal(audit.events[0].changeCounts.stock,1);
    assert.equal(Object.hasOwn(audit.events[0],'envelope'),false);
    assert.equal(JSON.stringify(audit).includes('chai'),false,'audit summary must not expose product identifiers');
    const altered={...event,payload:projectionDelta([{productId:'chai',quantity:8}])};
    await assert.rejects(service.submitEvents(actor,[altered]),(e)=>e instanceof ApiError&&e.code==='event_idempotency_conflict');
    const shift={shiftId:randomUUID(),sessionType:'staff_shift',openedAtEpochMs:Date.now(),closedAtEpochMs:null,openingCashPaise:1000,expectedCashPaise:1000,closingCashPaise:null};
    const shiftEvent={type:'business.command.v1',schemaVersion:1,eventId:randomUUID(),commandId:`shift-${randomUUID()}`,businessId:enrollment.businessId,shopId:enrollment.shopId,deviceId,baseRevision:1,payload:{...projectionDelta([]),staffShifts:[shift]}};
    assert.equal((await service.submitEvents(actor,[shiftEvent])).results[0].status,'accepted');
    const shiftReport=await service.shopStaffShifts(actor);
    assert.equal(shiftReport.shiftCount,1);
    assert.equal(shiftReport.shifts[0].shiftId,shift.shiftId);
    assert.match(shiftReport.evidence.limitation,/not attendance/);
    const latestAuditPage=await service.shopAuditHistory(actor,1);
    assert.equal(latestAuditPage.hasMore,true);
    assert.equal(latestAuditPage.events[0].serverRevision,2);
    assert.equal(latestAuditPage.nextBeforeRevision,2);
    const olderAuditPage=await service.shopAuditHistory(actor,1,latestAuditPage.nextBeforeRevision);
    assert.equal(olderAuditPage.hasMore,false);
    assert.equal(olderAuditPage.events[0].serverRevision,1);
    const closeEvent={type:'business.command.v1',schemaVersion:1,eventId:randomUUID(),commandId:`shift-close-${randomUUID()}`,businessId:enrollment.businessId,shopId:enrollment.shopId,deviceId,baseRevision:2,payload:{...projectionDelta([]),staffShifts:[{...shift,closedAtEpochMs:Date.now()+1000,expectedCashPaise:900,closingCashPaise:850}]}};
    assert.equal((await service.submitEvents(actor,[closeEvent])).results[0].status,'accepted');
    await assert.rejects(service.submitEvents(actor,[{...closeEvent,eventId:randomUUID(),commandId:`shift-alter-${randomUUID()}`,baseRevision:3,payload:{...closeEvent.payload,staffShifts:[{...closeEvent.payload.staffShifts[0],closingCashPaise:900}]}}]),(e)=>e instanceof ApiError&&e.code==='staff_shift_closed_identity_conflict');
    const stale={...event,eventId:randomUUID(),commandId:`refund-${randomUUID()}`};
    const conflict=await service.submitEvents(actor,[stale]);
    assert.equal(conflict.results[0].status,'conflict'); assert.equal(conflict.currentRevision,3);
    const secondStale={...stale,eventId:randomUUID(),commandId:`second-stale-${randomUUID()}`};
    assert.equal((await service.submitEvents(actor,[secondStale])).results[0].status,'conflict');
    const conflictQueue=await service.shopSyncConflicts(actor,10);
    assert.equal(conflictQueue.returnedConflictCount,2);
    assert.deepEqual(new Set(conflictQueue.conflicts.map((item)=>item.eventId)),new Set([stale.eventId,secondStale.eventId]));
    assert.equal(conflictQueue.conflicts[0].expectedRevision,0);
    assert.equal(conflictQueue.conflicts[0].currentRevision,3);
    assert.equal(conflictQueue.conflicts[0].changeCounts.orders,0);
    assert.equal(Object.hasOwn(conflictQueue.conflicts[0],'envelope'),false);
    assert.equal(JSON.stringify(conflictQueue).includes('chai'),false,'conflict review must not expose product identifiers');
    const conflictPage=await service.shopSyncConflicts(actor,1);
    assert.equal(conflictPage.returnedConflictCount,1);
    assert.equal(conflictPage.hasMore,true);
    const olderConflictPage=await service.shopSyncConflicts(actor,1,conflictPage.nextBefore);
    assert.equal(olderConflictPage.returnedConflictCount,1);
    assert.equal(olderConflictPage.hasMore,false);
    assert.notEqual(conflictPage.conflicts[0].eventId,olderConflictPage.conflicts[0].eventId);
    await assert.rejects(service.shopSyncConflicts(actor,10,{createdAt:'invalid',conflictId:stale.eventId}),(e)=>e instanceof ApiError&&e.code==='invalid_conflict_cursor');
    await assert.rejects(service.shopSyncConflicts(actor,101),(e)=>e instanceof ApiError&&e.code==='invalid_conflict_limit');
    server=createApiServer(service); await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    const conflictResponse=await fetch(`http://127.0.0.1:${server.address().port}/v1/dashboard/conflicts?shopId=${enrollment.shopId}`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(conflictResponse.status,200);
    assert.equal((await conflictResponse.json()).returnedConflictCount,2);
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
    if(server) await new Promise(resolve=>server.close(resolve));
    await cleanupEnrollment(admin,enrollment); await cleanupEnrollment(admin,otherEnrollment);
    await pool.end(); await admin.end();
  }
});

test('business owner branch directory is isolated and creates a separately authenticated branch atomically', { skip: !connection }, async () => {
  const pool=createPool(connection); const admin=createAdminPool(); const service=new SyncService(pool);
  const suffix=randomUUID(); const password='Synthetic-Only-Password-2026!'; const deviceId=randomUUID();
  let enrollment; let otherEnrollment; let server;
  try {
    enrollment=await service.register({email:`branch-${suffix}@example.test`,password,displayName:'Synthetic Owner',businessName:'Branch Business',shopName:'Main Shop',deviceId,deviceLabel:'Main Device'});
    const otherDeviceId=randomUUID();
    otherEnrollment=await service.register({email:`unrelated-${suffix}@example.test`,password,displayName:'Other Owner',businessName:'Other Business',shopName:'Other Main',deviceId:otherDeviceId,deviceLabel:'Other Device'});
    const actor=await service.authenticate(enrollment.token,enrollment.shopId);
    await assert.rejects(service.createBusinessBranch({...actor,role:'cashier'},{displayName:'Denied Branch',deviceLabel:'No Device'}),
      (error)=>error instanceof ApiError&&error.status===403&&error.code==='owner_access_required');
    await assert.rejects(service.revokeBusinessDevice({...actor,role:'cashier'},{targetShopId:enrollment.shopId,deviceId}),
      (error)=>error instanceof ApiError&&error.status===403&&error.code==='owner_access_required');
    await assert.rejects(service.registerBusinessDevice({...actor,role:'cashier'},{targetShopId:enrollment.shopId,label:'Denied device'}),
      (error)=>error instanceof ApiError&&error.status===403&&error.code==='owner_access_required');
    await assert.rejects(service.renameBusinessBranch({...actor,role:'cashier'},{targetShopId:enrollment.shopId,expectedName:'Main Shop',displayName:'Denied Rename'}),
      (error)=>error instanceof ApiError&&error.status===403&&error.code==='owner_access_required');
    await assert.rejects(service.createBusinessBranch(actor,{displayName:'x',deviceLabel:'No Device'}),
      (error)=>error instanceof ApiError&&error.status===400&&error.code==='invalid_branch_details');
    server=createApiServer(service); await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    const base=`http://127.0.0.1:${server.address().port}`;
    const auth={headers:{authorization:`Bearer ${enrollment.token}`}};
    assert.equal((await fetch(`${base}/v1/dashboard/branches?shopId=${enrollment.shopId}`)).status,401);
    const createdResponse=await fetch(`${base}/v1/dashboard/branches`,{method:'POST',...auth,headers:{...auth.headers,'content-type':'application/json'},body:JSON.stringify({shopId:enrollment.shopId,displayName:'Second Shop',deviceLabel:'Second Counter'})});
    assert.equal(createdResponse.status,201);
    const created=await createdResponse.json();
    assert.equal(created.branch.displayName,'Second Shop');
    assert.match(created.branch.shopId,/^[0-9a-f-]{36}$/i);
    assert.match(created.deviceId,/^[0-9a-f-]{36}$/i);
    const listed=await fetch(`${base}/v1/dashboard/branches?shopId=${enrollment.shopId}`,auth);
    assert.equal(listed.status,200);
    const directory=await listed.json();
    assert.equal(directory.branches.length,2);
    assert.equal(directory.branches.find((branch)=>branch.shopId===enrollment.shopId).current,true);
    const newBranch = directory.branches.find((branch)=>branch.shopId===created.branch.shopId);
    assert.equal(newBranch.current,false);
    assert.deepEqual(newBranch.devices.map((device)=>device.deviceId),[created.deviceId]);
    assert.equal(newBranch.devices[0].label,'Second Counter');
    assert.equal(directory.branches.some((branch)=>branch.shopId===otherEnrollment.shopId),false);
    assert.equal(directory.branches.flatMap((branch)=>branch.devices).some((device)=>device.deviceId===otherEnrollment.deviceId),false);
    assert.equal(JSON.stringify(directory).includes('Other Business'),false);
    const duplicateBody=await fetch(`${base}/v1/dashboard/branches`,{method:'POST',...auth,headers:{...auth.headers,'content-type':'application/json'},body:JSON.stringify({shopId:enrollment.shopId,displayName:'Second Shop',deviceLabel:'Third Counter'})});
    assert.equal(duplicateBody.status,201,'distinct branches may use the same display name; IDs remain authoritative');
    const duplicate = await duplicateBody.json();
    const renamed = await service.renameBusinessBranch(actor,{targetShopId:created.branch.shopId,expectedName:'Second Shop',displayName:'North Counter'});
    assert.deepEqual(renamed,{shopId:created.branch.shopId,displayName:'North Counter',state:'renamed'});
    assert.deepEqual(await service.renameBusinessBranch(actor,{targetShopId:created.branch.shopId,expectedName:'North Counter',displayName:'North Counter'}),
      {shopId:created.branch.shopId,displayName:'North Counter',state:'unchanged'});
    await assert.rejects(service.renameBusinessBranch(actor,{targetShopId:created.branch.shopId,expectedName:'Second Shop',displayName:'Stale Rename'}),
      (error)=>error instanceof ApiError&&error.status===409&&error.code==='branch_name_conflict');
    await assert.rejects(service.renameBusinessBranch(actor,{targetShopId:otherEnrollment.shopId,expectedName:'Other Main',displayName:'Not Yours'}),
      (error)=>error instanceof ApiError&&error.status===404&&error.code==='business_branch_not_found');
    const login=await service.login({email:`branch-${suffix}@example.test`,password,shopId:created.branch.shopId,deviceId:created.deviceId});
    assert.equal(login.role,'owner');
    assert.equal(login.businessId,enrollment.businessId);
    const branchActor=await service.authenticate(login.token,created.branch.shopId);
    assert.equal(branchActor.user_id,enrollment.userId);
    await assert.rejects(service.revokeBusinessDevice(actor,{targetShopId:enrollment.shopId,deviceId}),
      (error)=>error instanceof ApiError&&error.status===409&&error.code==='current_device_cannot_be_revoked');
    const revocation=await service.revokeBusinessDevice(actor,{targetShopId:created.branch.shopId,deviceId:created.deviceId});
    assert.deepEqual(revocation,{shopId:created.branch.shopId,deviceId:created.deviceId,state:'revoked',revokedSessionCount:1});
    await assert.rejects(service.authenticate(login.token,created.branch.shopId),(error)=>error instanceof ApiError&&error.status===401);
    await assert.rejects(service.login({email:`branch-${suffix}@example.test`,password,shopId:created.branch.shopId,deviceId:created.deviceId}),
      (error)=>error instanceof ApiError&&error.status===403&&error.code==='shop_or_device_access_denied');
    assert.equal((await service.revokeBusinessDevice(actor,{targetShopId:created.branch.shopId,deviceId:created.deviceId})).state,'already_revoked');
    const revokedDirectory=(await service.ownerBranches(actor)).branches.find((branch)=>branch.shopId===created.branch.shopId);
    assert.equal(revokedDirectory.devices[0].revoked,true);
    const replacement=await service.registerBusinessDevice(actor,{targetShopId:created.branch.shopId,label:'Replacement Counter'});
    assert.equal(replacement.state,'active');
    assert.equal(replacement.label,'Replacement Counter');
    assert.match(replacement.deviceId,/^[0-9a-f-]{36}$/i);
    const replacementLogin=await service.login({email:`branch-${suffix}@example.test`,password,shopId:created.branch.shopId,deviceId:replacement.deviceId});
    assert.equal(replacementLogin.role,'owner');
    assert.equal((await service.authenticate(replacementLogin.token,created.branch.shopId)).device_id,replacement.deviceId);
    const replacementDirectory=(await service.ownerBranches(actor)).branches.find((branch)=>branch.shopId===created.branch.shopId);
    assert.equal(replacementDirectory.devices.find((device)=>device.deviceId===replacement.deviceId).revoked,false);
    await assert.rejects(service.revokeBusinessDevice(actor,{targetShopId:otherEnrollment.shopId,deviceId:otherDeviceId}),
      (error)=>error instanceof ApiError&&error.status===404&&error.code==='business_device_not_found');
    await assert.rejects(service.authenticate(enrollment.token,otherEnrollment.shopId),(error)=>error instanceof ApiError&&error.status===401);
    const audit=await withTransaction(pool,async(c)=>{
      await c.query("SELECT set_config('app.current_business',$1,true)",[enrollment.businessId]);
      await c.query("SELECT set_config('app.current_user',$1,true)",[enrollment.userId]);
      return c.query("SELECT action,device_id,details FROM business_branch_audit WHERE business_id=$1 AND actor_user_id=$2",[enrollment.businessId,enrollment.userId]);
    });
    assert.equal(audit.rows.length,5);
    assert.ok(audit.rows.some((event)=>event.action==='branch_renamed'&&event.details.previousName==='Second Shop'&&event.details.displayName==='North Counter'));
    assert.ok(audit.rows.some((event)=>event.action==='device_revoked'&&event.device_id===created.deviceId));
    assert.ok(audit.rows.some((event)=>event.action==='device_registered'&&event.device_id===replacement.deviceId));
    const hidden=await withTransaction(pool,async(c)=>{
      await c.query("SELECT set_config('app.current_business',$1,true)",[enrollment.businessId]);
      await c.query("SELECT set_config('app.current_user',$1,true)",[otherEnrollment.userId]);
      return c.query('SELECT count(*)::int AS count FROM business_memberships WHERE business_id=$1',[enrollment.businessId]);
    });
    assert.equal(hidden.rows[0].count,0,'RLS hides another owner principal');
  } finally {
    if(server) await new Promise(resolve=>server.close(resolve));
    await cleanupEnrollment(admin,enrollment); await cleanupEnrollment(admin,otherEnrollment);
    await pool.end(); await admin.end();
  }
});

test('accepted unit labels persist in owner projections, reject relabeling, and preserve retry/conflict behavior', { skip: !connection }, async () => {
  const pool = createPool(connection); const admin = createAdminPool(); const service = new SyncService(pool); const deviceId = randomUUID();
  let enrollment;
  try {
    enrollment = await service.register({ email:`unit-${randomUUID()}@example.test`, password:'Synthetic-Only-Password-2026!', displayName:'Synthetic Owner', businessName:'Synthetic Business', shopName:'Synthetic Shop', deviceId, deviceLabel:'Unit Test Device' });
    const actor = await service.authenticate(enrollment.token, enrollment.shopId);
    const event = { type:'business.command.v1', schemaVersion:1, eventId:randomUUID(), commandId:`unit-${randomUUID()}`, businessId:enrollment.businessId, shopId:enrollment.shopId, deviceId, baseRevision:0, payload:{ ...projectionDelta([]), stock:[{ productId:'tea', quantity:4, unitLabel:'cup' }] } };
    assert.equal((await service.submitEvents(actor, [event])).results[0].status, 'accepted');
    assert.equal((await service.submitEvents(actor, [event])).results[0].status, 'duplicate');

    const relabel = { ...event, eventId:randomUUID(), commandId:`relabel-${randomUUID()}`, baseRevision:1, payload:{ ...projectionDelta([]), stock:[{ productId:'tea', quantity:4, unitLabel:'piece' }] } };
    await assert.rejects(service.submitEvents(actor, [relabel]), (error) => error instanceof ApiError && error.code === 'product_unit_label_conflict');
    const stale = { ...relabel, eventId:randomUUID(), commandId:`stale-${randomUUID()}`, baseRevision:0 };
    assert.equal((await service.submitEvents(actor, [stale])).results[0].status, 'conflict');

    const inventory = await service.shopInventorySnapshot(actor);
    assert.equal(inventory.products.find((product) => product.productId === 'tea').unitLabel, 'cup');
    assert.equal((await service.shopProductPerformance(actor)).products.find((product) => product.productId === 'tea').unitLabel, 'cup');
  } finally {
    if (enrollment) await cleanupEnrollment(admin, enrollment);
    await pool.end(); await admin.end();
  }
});

test('owner catalogue upserts reach product and inventory views and freeze pack conversion after use', { skip: !connection }, async () => {
  const pool = createPool(connection); const admin = createAdminPool(); const service = new SyncService(pool); const deviceId = randomUUID();
  let enrollment;
  try {
    enrollment = await service.register({ email:`catalogue-${randomUUID()}@example.test`, password:'Synthetic-Only-Password-2026!', displayName:'Synthetic Owner', businessName:'Synthetic Business', shopName:'Synthetic Shop', deviceId, deviceLabel:'Catalogue Test Device' });
    const actor = await service.authenticate(enrollment.token, enrollment.shopId);
    const product = { productId:'water_bottle', name:'Water Bottle', category:'Drinks', unitPricePaise:2000, active:true, favorite:false,
      priceConfirmed:true, aliases:['water'], reorderThreshold:12, estimatedUnitCostPaise:125, scanCode:null,
      unitLabel:'bottle', purchaseUnitLabel:'crate', baseUnitsPerPurchaseUnit:24 };
    const catalogueEvent = { type:'business.command.v1', schemaVersion:1, eventId:randomUUID(), commandId:`catalogue-${randomUUID()}`,
      businessId:enrollment.businessId, shopId:enrollment.shopId, deviceId, baseRevision:0,
      payload:{ ...projectionDelta([]), catalogue:[product] } };
    assert.equal((await service.submitEvents(actor, [catalogueEvent])).results[0].status, 'accepted');
    assert.equal((await service.submitEvents(actor, [catalogueEvent])).results[0].status, 'duplicate');
    const shopCatalogue = await service.shopCatalogue(actor);
    assert.equal(shopCatalogue.catalogueCount, 1);
    assert.equal(shopCatalogue.catalogue[0].name, 'Water Bottle');
    assert.equal(shopCatalogue.catalogue[0].baseUnitsPerPurchaseUnit, 24);
    assert.equal(Object.hasOwn(shopCatalogue.catalogue[0], 'estimatedUnitCostPaise'), false);
    const performance = await service.shopProductPerformance(actor);
    assert.equal(performance.products[0].productName, 'Water Bottle');
    assert.equal(performance.products[0].purchaseUnitLabel, 'crate');
    assert.equal(performance.products[0].baseUnitsPerPurchaseUnit, 24);
    assert.equal(performance.products[0].stockState, 'unreported');

    const stockEvent = { ...catalogueEvent, eventId:randomUUID(), commandId:`stock-${randomUUID()}`, baseRevision:1,
      payload:{ ...projectionDelta([{ productId:'water_bottle', quantity:48, unitLabel:'bottle' }]) } };
    assert.equal((await service.submitEvents(actor, [stockEvent])).results[0].status, 'accepted');
    const inventory = await service.shopInventorySnapshot(actor);
    assert.equal(inventory.products[0].productName, 'Water Bottle');
    assert.equal(inventory.products[0].quantity, 48);
    assert.equal(inventory.products[0].baseUnitsPerPurchaseUnit, 24);

    const relabelConversion = { ...catalogueEvent, eventId:randomUUID(), commandId:`factor-${randomUUID()}`, baseRevision:2,
      payload:{ ...projectionDelta([]), catalogue:[{ ...product, baseUnitsPerPurchaseUnit:12 }] } };
    await assert.rejects(service.submitEvents(actor, [relabelConversion]), (error) => error instanceof ApiError && error.code === 'product_conversion_conflict');
    const priceUpdate = { ...catalogueEvent, eventId:randomUUID(), commandId:`price-${randomUUID()}`, baseRevision:2,
      payload:{ ...projectionDelta([]), catalogue:[{ ...product, unitPricePaise:2100 }] } };
    assert.equal((await service.submitEvents(actor, [priceUpdate])).results[0].status, 'accepted');
    assert.equal((await service.shopProductPerformance(actor)).products[0].unitPricePaise, 2100);
  } finally {
    if (enrollment) await cleanupEnrollment(admin, enrollment);
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

test('owner staff invitations create least-privilege memberships and removal revokes sessions', { skip: !connection }, async()=>{
  const pool=createPool(connection); const admin=createAdminPool(); const service=new SyncService(pool); const deviceId=randomUUID();
  const suffix=randomUUID(); let owner; let existingAccount; let staffUserId; let invitationId; let server; let cashierSaleEventId;
  try {
    owner=await service.register({email:`staff-owner-${suffix}@example.test`,password:'Synthetic-Only-Password-2026!',displayName:'Synthetic Owner',businessName:'Synthetic Business',shopName:'Synthetic Shop',deviceId,deviceLabel:'Owner Test Device'});
    const ownerActor=await service.authenticate(owner.token,owner.shopId);
    assert.equal((await pool.query('SELECT user_id FROM shop_memberships WHERE shop_id=$1',[owner.shopId])).rowCount,0,'RLS must hide memberships without an explicit shop scope');
    const hiddenMemberships=await withTransaction(pool,async(c)=>{
      await c.query("SELECT set_config('app.current_shop',$1,true)",[randomUUID()]);
      return c.query('SELECT user_id FROM shop_memberships WHERE shop_id=$1',[owner.shopId]);
    });
    assert.equal(hiddenMemberships.rowCount,0,'RLS must prevent a wrong-shop membership lookup');
    server=createApiServer(service); await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    const base=`http://127.0.0.1:${server.address().port}`;
    const invitationResponse=await fetch(`${base}/v1/staff/invitations`,{method:'POST',headers:{authorization:`Bearer ${owner.token}`,'content-type':'application/json'},body:JSON.stringify({shopId:owner.shopId,email:`staff-${suffix}@example.test`,role:'cashier'})});
    assert.equal(invitationResponse.status,201);
    const invitation=await invitationResponse.json(); invitationId=invitation.invitationId;
    assert.match(invitation.invitationToken,/^[A-Za-z0-9_-]{43}$/);
    assert.ok(new Date(invitation.expiresAt)>new Date());
    await assert.rejects(service.createStaffInvitation(ownerActor,{email:`owner-${suffix}@example.test`,role:'owner'}),(e)=>e instanceof ApiError&&e.status===400);
    const listBefore=await fetch(`${base}/v1/staff/invitations?shopId=${owner.shopId}`,{headers:{authorization:`Bearer ${owner.token}`}}).then(r=>r.json());
    assert.equal(listBefore.invitations[0].state,'pending');
    assert.equal(Object.hasOwn(listBefore.invitations[0],'invitationToken'),false);
    assert.equal((await pool.query('SELECT invitation_id FROM staff_invitations WHERE invitation_id=$1',[invitationId])).rowCount,0,'RLS must hide invite rows outside the shop transaction scope');
    assert.equal((await pool.query('SELECT audit_id FROM shop_staff_audit')).rowCount,0,'RLS must hide staff audit rows outside the shop transaction scope');
    const staffDeviceId=randomUUID(); const password='Synthetic-Staff-Password-2026!';
    const acceptedResponse=await fetch(`${base}/v1/staff/accept-invitation`,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({invitationToken:invitation.invitationToken,displayName:'Synthetic Cashier',password,deviceId:staffDeviceId,deviceLabel:'Staff Test Device'})});
    assert.equal(acceptedResponse.status,201);
    const accepted=await acceptedResponse.json(); staffUserId=accepted.userId;
    assert.equal(accepted.role,'cashier'); assert.equal(accepted.shopId,owner.shopId); assert.ok(accepted.token);
    await assert.rejects(service.acceptStaffInvitation({invitationToken:invitation.invitationToken,displayName:'Duplicate',password,deviceId:randomUUID(),deviceLabel:'Duplicate Device'}),(e)=>e instanceof ApiError&&e.status===401);
    const staffActor=await service.authenticate(accepted.token,owner.shopId);
    assert.equal(staffActor.role,'cashier');
    await assert.rejects(service.shopSyncConflicts(staffActor),(e)=>e instanceof ApiError&&e.status===403&&e.code==='owner_access_required');
    const deniedExpense={type:'business.command.v1',schemaVersion:1,eventId:randomUUID(),commandId:`cashier-expense-${randomUUID()}`,businessId:owner.businessId,shopId:owner.shopId,deviceId:staffDeviceId,baseRevision:0,payload:{...projectionDelta([]),expenses:[{expenseId:'cashier-expense',category:'transport',amountPaise:500,method:'cash',status:'staff_declared'}]}};
    await assert.rejects(service.submitEvents(staffActor,[deniedExpense]),(e)=>e instanceof ApiError&&e.status===403&&e.code==='cashier_business_permission_denied');
    const afterDenied=await withTransaction(pool,async(c)=>{await c.query("SELECT set_config('app.current_shop',$1,true)",[owner.shopId]);return c.query('SELECT server_revision FROM shops WHERE shop_id=$1',[owner.shopId]);});
    assert.equal(Number(afterDenied.rows[0].server_revision),0,'denied staff writes must not advance the journal');
    const openingStock={type:'business.command.v1',schemaVersion:1,eventId:randomUUID(),commandId:`owner-stock-${randomUUID()}`,businessId:owner.businessId,shopId:owner.shopId,deviceId,baseRevision:0,payload:projectionDelta([{productId:'chai',quantity:5}])};
    assert.equal((await service.submitEvents(ownerActor,[openingStock])).results[0].status,'accepted');
    cashierSaleEventId=randomUUID();
    const cashierSale={type:'business.command.v1',schemaVersion:1,eventId:cashierSaleEventId,commandId:`cashier-sale-${randomUUID()}`,businessId:owner.businessId,shopId:owner.shopId,deviceId:staffDeviceId,baseRevision:1,payload:{...projectionDelta([{productId:'chai',quantity:4}]),orders:[{orderNumber:1,fulfillment:'GIVEN',payment:'STAFF_DECLARED',paidPaise:1000,creditPaise:0,refundedPaise:0,cashRefundedPaise:0,creditReversedPaise:0,salePosted:true,voided:false,revision:2,createdAtEpochMs:1000,lines:[{productId:'chai',quantity:1,deliveredQuantity:1,refundedQuantity:0,unitPricePaise:1000}] }],stockMovements:[{movementId:`cashier-move-${randomUUID()}`,productId:'chai',quantityDelta:-1,orderNumber:1}],paymentEntries:[{entryId:randomUUID(),orderNumber:1,customerId:null,amountPaise:1000,method:'cash',status:'staff_declared',kind:'order_collection',relatedEntryId:null}]}};
    assert.equal((await service.submitEvents(staffActor,[cashierSale])).results[0].status,'accepted');
    await assert.rejects(service.shopStaffMembers(staffActor),(e)=>e instanceof ApiError&&e.status===403);
    await assert.rejects(service.createStaffInvitation(staffActor,{email:`another-${suffix}@example.test`,role:'cashier'}),(e)=>e instanceof ApiError&&e.status===403);
    const ownerMembers=await service.shopStaffMembers(ownerActor);
    assert.equal(ownerMembers.members.find(m=>m.userId===staffUserId).role,'cashier');
    const promoted=await service.setStaffMemberRole(ownerActor,staffUserId,'manager');
    assert.equal(promoted.role,'manager');
    assert.equal((await service.authenticate(accepted.token,owner.shopId)).role,'manager');
    const managerExpense={type:'business.command.v1',schemaVersion:1,eventId:randomUUID(),commandId:`manager-expense-${randomUUID()}`,businessId:owner.businessId,shopId:owner.shopId,deviceId:staffDeviceId,baseRevision:2,payload:{...projectionDelta([]),expenses:[{expenseId:'manager-expense',category:'transport',amountPaise:500,method:'cash',status:'staff_declared'}]}};
    assert.equal((await service.submitEvents(await service.authenticate(accepted.token,owner.shopId),[managerExpense])).results[0].status,'accepted');
    existingAccount=await service.register({email:`existing-staff-${suffix}@example.test`,password:'Existing-Staff-Password-2026!',displayName:'Existing Account Owner',businessName:'Separate Business',shopName:'Separate Shop',deviceId:randomUUID(),deviceLabel:'Existing Account Device'});
    const existingInvite=await service.createStaffInvitation(ownerActor,{email:`existing-staff-${suffix}@example.test`,role:'cashier'});
    const targetDeviceId=randomUUID();
    await assert.rejects(service.acceptStaffInvitation({invitationToken:existingInvite.invitationToken,displayName:'Ignored Name',password:'Incorrect-Existing-Password!',deviceId:targetDeviceId,deviceLabel:'Target Device'}),(e)=>e instanceof ApiError&&e.status===401);
    assert.equal((await service.shopStaffInvitations(ownerActor)).invitations.find(item=>item.invitationId===existingInvite.invitationId).state,'pending');
    const acceptedExisting=await service.acceptStaffInvitation({invitationToken:existingInvite.invitationToken,displayName:'Ignored Name',password:'Existing-Staff-Password-2026!',deviceId:targetDeviceId,deviceLabel:'Target Device'});
    assert.equal(acceptedExisting.userId,existingAccount.userId);
    assert.equal(acceptedExisting.role,'cashier');
    assert.equal((await service.login({email:`existing-staff-${suffix}@example.test`,password:'Existing-Staff-Password-2026!',shopId:owner.shopId,deviceId:targetDeviceId})).role,'cashier');
    await assert.rejects(service.setStaffMemberRole(ownerActor,owner.userId,'cashier'),(e)=>e instanceof ApiError&&e.status===400);
    await assert.rejects(service.removeStaffMember(ownerActor,owner.userId),(e)=>e instanceof ApiError&&e.status===400);
    const revoked=await service.removeStaffMember(ownerActor,staffUserId);
    assert.equal(revoked.sessionsRevoked,true);
    await assert.rejects(service.authenticate(accepted.token,owner.shopId),(e)=>e instanceof ApiError&&e.status===401);
    await service.removeStaffMember(ownerActor,existingAccount.userId);
    const removed=await fetch(`${base}/v1/staff/members/${staffUserId}?shopId=${owner.shopId}`,{method:'DELETE',headers:{authorization:`Bearer ${owner.token}`}});
    assert.equal(removed.status,404);
    await assert.rejects(service.revokeStaffInvitation(ownerActor,invitationId),(e)=>e instanceof ApiError&&e.status===404);
    assert.equal((await service.shopStaffInvitations(ownerActor)).invitations[0].state,'accepted');
    const auditResponse=await fetch(`${base}/v1/staff/audit?shopId=${owner.shopId}`,{headers:{authorization:`Bearer ${owner.token}`}});
    assert.equal(auditResponse.status,200);
    const audit=await auditResponse.json();
    assert.deepEqual(audit.events.map(event=>event.action).sort(),['invitation_accepted','invitation_accepted','invitation_created','invitation_created','membership_removed','membership_removed','role_changed'].sort());
    assert.equal(audit.events.find(event=>event.action==='role_changed').previousRole,'cashier');
    assert.equal(audit.events.find(event=>event.action==='role_changed').nextRole,'manager');
    assert.equal(JSON.stringify(audit).includes(invitation.invitationToken),false);
    const businessAudit=await service.shopAuditHistory(ownerActor,100);
    const attributedSale=businessAudit.events.find((event)=>event.eventId===cashierSaleEventId);
    assert.equal(attributedSale.actorUserId,staffUserId);
    assert.equal(attributedSale.actorRole,'cashier');
    assert.equal(attributedSale.actorDisplayName,'Synthetic Cashier');
    const attributedManager=(await service.shopAuditHistory(ownerActor,100)).events.find((event)=>event.eventId===managerExpense.eventId);
    assert.equal(attributedManager.actorRole,'manager');
    await assert.rejects(pool.query('DELETE FROM shop_staff_audit WHERE shop_id=$1',[owner.shopId]),(e)=>e.code==='42501');
    const revokedInvite=await service.createStaffInvitation(ownerActor,{email:`revoked-${suffix}@example.test`,role:'cashier'});
    const revokeResponse=await fetch(`${base}/v1/staff/invitations/${revokedInvite.invitationId}?shopId=${owner.shopId}`,{method:'DELETE',headers:{authorization:`Bearer ${owner.token}`}});
    assert.equal(revokeResponse.status,200);
    await assert.rejects(service.acceptStaffInvitation({invitationToken:revokedInvite.invitationToken,displayName:'Revoked Staff',password,deviceId:randomUUID(),deviceLabel:'Revoked Device'}),(e)=>e instanceof ApiError&&e.status===401);
    const finalAudit=await service.shopStaffAudit(ownerActor);
    assert.ok(finalAudit.events.some(event=>event.action==='invitation_revoked'));
  } finally {
    if(server) await new Promise(resolve=>server.close(resolve));
    if(owner) await withTransaction(admin,async(c)=>{
      await c.query("SELECT set_config('app.current_shop',$1,true)",[owner.shopId]);
      await c.query('DELETE FROM sessions WHERE shop_id=$1',[owner.shopId]);
      await c.query('DELETE FROM shop_staff_audit WHERE shop_id=$1',[owner.shopId]);
      await c.query('DELETE FROM staff_invitations WHERE shop_id=$1',[owner.shopId]);
      await c.query('DELETE FROM business_branch_audit WHERE business_id=$1',[owner.businessId]);
      await c.query('DELETE FROM devices WHERE shop_id=$1',[owner.shopId]);
      await c.query('DELETE FROM shop_memberships WHERE shop_id=$1',[owner.shopId]);
      await c.query('DELETE FROM sync_conflicts WHERE shop_id=$1',[owner.shopId]);
      await c.query('DELETE FROM sync_events WHERE shop_id=$1',[owner.shopId]);
      await c.query('DELETE FROM shops WHERE shop_id=$1',[owner.shopId]);
      await c.query('DELETE FROM business_memberships WHERE business_id=$1',[owner.businessId]);
      await c.query('DELETE FROM businesses WHERE business_id=$1',[owner.businessId]);
      await c.query('DELETE FROM users WHERE user_id=ANY($1::uuid[])',[[owner.userId,...(staffUserId?[staffUserId]:[])]]);
    });
    await cleanupEnrollment(admin,existingAccount);
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
    const supplierId=randomUUID(); const receiptId=randomUUID();
    const opaqueCustomerId=randomUUID(); const creditId=randomUUID(); const repaymentId=randomUUID();
    const event={type:'business.command.v1',schemaVersion:1,eventId:randomUUID(),commandId:`ui-sale-${randomUUID()}`,businessId:enrollment.businessId,shopId:enrollment.shopId,deviceId,baseRevision:0,payload:{...projectionDelta([{productId:'chai',quantity:4}]),purchaseReceipts:[{receiptId,supplierId,totalPaise:5000}],supplierPayments:[{paymentId:randomUUID(),supplierId,amountPaise:1000,method:'cash',status:'staff_declared'}],purchaseReturns:[{returnId:randomUUID(),supplierId,receiptId,amountPaise:500}],expenses:[{
      expenseId,category:'utilities',amountPaise:25050,method:'cash',status:'staff_declared'
    }],orders:[{
      orderNumber:12,fulfillment:'GIVEN',payment:'STAFF_DECLARED',paidPaise:2000,creditPaise:0,refundedPaise:0,cashRefundedPaise:0,creditReversedPaise:0,
      salePosted:true,voided:false,revision:1,createdAtEpochMs:1000,
      lines:[{productId:'chai',quantity:2,deliveredQuantity:2,refundedQuantity:0,unitPricePaise:1000,estimatedUnitCostPaise:400}]
    },{
      orderNumber:13,fulfillment:'TAKEN',payment:'UPI_PENDING',paidPaise:0,creditPaise:0,refundedPaise:0,cashRefundedPaise:0,creditReversedPaise:0,
      salePosted:false,voided:false,revision:1,createdAtEpochMs:2000,
      lines:[{productId:'water',quantity:1,deliveredQuantity:0,refundedQuantity:0,unitPricePaise:1700,estimatedUnitCostPaise:800}]
    }],customerCredits:[{creditId,customerId:opaqueCustomerId,amountPaise:500,repaidPaise:200,reversedPaise:0}],
      repayments:[{repaymentId,customerId:opaqueCustomerId,amountPaise:200}],
      paymentEntries:[{entryId:randomUUID(),orderNumber:12,customerId:null,amountPaise:2000,method:'cash',status:'staff_declared',kind:'order_collection',relatedEntryId:null},
        {entryId:randomUUID(),orderNumber:null,customerId:opaqueCustomerId,amountPaise:200,method:'cash',status:'staff_declared',kind:'customer_repayment',relatedEntryId:null},
        {entryId:randomUUID(),orderNumber:13,customerId:null,amountPaise:1700,method:'upi',status:'pending',kind:'payment_attempt',relatedEntryId:null}],
      catalogue:[{productId:'chai',name:'Masala Chai',category:'Tea',unitPricePaise:1000,active:true,favorite:true,priceConfirmed:true,aliases:['chai','masala chai'],reorderThreshold:10,estimatedUnitCostPaise:400,scanCode:'chai-001',unitLabel:'cup',purchaseUnitLabel:'crate',baseUnitsPerPurchaseUnit:24}]}};
    const sync=await fetch(`${base}/v1/sync/events`,{method:'POST',headers:{'content-type':'application/json',authorization:`Bearer ${enrollment.token}`},body:JSON.stringify({shopId:enrollment.shopId,events:[event]})});
    assert.equal(sync.status,200); assert.equal((await sync.json()).results[0].status,'accepted');
    const overview=await fetch(`${base}/v1/dashboard/overview?shopId=${enrollment.shopId}`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(overview.status,200); const summary=await overview.json();
    assert.equal(summary.shopId,enrollment.shopId); assert.equal(summary.recordedOrderCount,2); assert.equal(summary.openOrderCount,1);
    assert.equal(summary.netDeliveredItemValuePaise,2000); assert.equal(summary.cashCollectedPaise,2200);
    assert.equal(summary.trackedProductCount,1); assert.match(summary.evidence.cash,/Staff-declared/);
    const insightsResponse=await fetch(`${base}/v1/dashboard/insights?shopId=${enrollment.shopId}`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(insightsResponse.status,200); const insights=await insightsResponse.json();
    assert.deepEqual(insights.insights.map(item=>item.id),['open_orders','upi_pending','customer_receivables','supplier_payables']);
    assert.equal(insights.insights[1].amountPaise,1700); assert.equal(insights.insights[2].amountPaise,300); assert.equal(insights.insights[3].amountPaise,3500);
    assert.equal(JSON.stringify(insights).includes(opaqueCustomerId),false); assert.equal(JSON.stringify(insights).includes(supplierId),false);
    const insightsWrongShop=await fetch(`${base}/v1/dashboard/insights?shopId=${randomUUID()}`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(insightsWrongShop.status,401);
    const products=await fetch(`${base}/v1/dashboard/products?shopId=${enrollment.shopId}`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(products.status,200); const performance=await products.json();
    assert.equal(performance.shopId,enrollment.shopId); assert.equal(performance.productCount,1);
    assert.equal(performance.products[0].productId,'chai'); assert.equal(performance.products[0].netDeliveredUnits,2);
    assert.equal(performance.products[0].netDeliveredItemValuePaise,2000); assert.equal(performance.products[0].stockQuantity,4);
    assert.match(performance.evidence.itemValue,/not recognized profit/);
    const catalogueResponse=await fetch(`${base}/v1/sync/catalogue/${enrollment.shopId}`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(catalogueResponse.status,200); const shopCatalogue=await catalogueResponse.json();
    assert.equal(shopCatalogue.catalogueCount,1); assert.equal(shopCatalogue.serverRevision,1);
    assert.equal(shopCatalogue.catalogue[0].name,'Masala Chai'); assert.equal(shopCatalogue.catalogue[0].unitPricePaise,1000);
    assert.deepEqual(shopCatalogue.catalogue[0].aliases,['chai','masala chai']);
    assert.equal(shopCatalogue.catalogue[0].baseUnitsPerPurchaseUnit,24);
    assert.equal(Object.hasOwn(shopCatalogue.catalogue[0],'estimatedUnitCostPaise'),false);
    assert.equal(Object.hasOwn(shopCatalogue.catalogue[0],'scanCode'),true);
    const catalogueNoAuth=await fetch(`${base}/v1/sync/catalogue/${enrollment.shopId}`);
    assert.equal(catalogueNoAuth.status,401);
    const supplierResponse=await fetch(`${base}/v1/dashboard/suppliers?shopId=${enrollment.shopId}`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(supplierResponse.status,200); const supplierSummary=await supplierResponse.json();
    assert.equal(supplierSummary.supplierCount,1); assert.equal(supplierSummary.totalReceiptsPaise,5000);
    assert.equal(supplierSummary.totalPaymentsPaise,1000); assert.equal(supplierSummary.totalReturnsPaise,500);
    assert.equal(supplierSummary.netPayablePaise,3500); assert.equal(supplierSummary.suppliers[0].supplierId,supplierId);
    assert.match(supplierSummary.evidence.verification,/not bank-verified/);
    const customers=await fetch(`${base}/v1/dashboard/customers?shopId=${enrollment.shopId}`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(customers.status,200); const customerSummary=await customers.json();
    assert.equal(customerSummary.customerCount,1); assert.equal(customerSummary.totalExtendedPaise,500);
    assert.equal(customerSummary.totalRepaymentsPaise,200); assert.equal(customerSummary.netReceivablePaise,300);
    assert.equal(customerSummary.customers[0].customerId,opaqueCustomerId); assert.equal(customerSummary.customers[0].repaidPaise,200);
    assert.equal(JSON.stringify(customerSummary).includes('local_customer'),false);
    const activity=await fetch(`${base}/v1/dashboard/activity?shopId=${enrollment.shopId}&days=7`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(activity.status,200); const daily=await activity.json();
    assert.equal(daily.shopId,enrollment.shopId); assert.equal(daily.serverRevision,1);
    assert.equal(daily.acceptedEventCount,1); assert.equal(daily.totals.staffDeclaredExpensesPaise,25050);
    assert.equal(daily.totals.staffDeclaredSupplierPaymentsPaise,1000);
    assert.deepEqual(daily.expenseCategories,[{category:'utilities',count:1,amountPaise:25050}]);
    assert.equal(daily.daily.reduce((sum,day)=>sum+day.staffDeclaredExpensesPaise,0),25050);
    assert.match(daily.evidence.expenses,/not a profit calculation/);
    const cashflow=await fetch(`${base}/v1/dashboard/cashflow?shopId=${enrollment.shopId}&days=7`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(cashflow.status,200); const movement=await cashflow.json();
    assert.equal(movement.shopId,enrollment.shopId); assert.equal(movement.serverRevision,1);
    assert.equal(movement.totals.declaredCashInPaise,2200); assert.equal(movement.totals.declaredUpiInPaise,0);
    assert.equal(movement.totals.recordedExpensesOutPaise,25050);
    assert.equal(movement.totals.recordedSupplierPaymentsOutPaise,1000);
    assert.equal(movement.totals.netRecordedMovementPaise,-23850);
    assert.match(movement.evidence.outflows,/not bank-verified/);
    assert.match(movement.evidence.net,/not profit/);
    const profit=await fetch(`${base}/v1/dashboard/profit-readiness?shopId=${enrollment.shopId}&days=7`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(profit.status,200); const readiness=await profit.json();
    assert.equal(readiness.shopId,enrollment.shopId); assert.equal(readiness.status,'not_calculable');
    assert.equal(readiness.figures.netDeliveredItemValueChangePaise,2000);
    assert.equal(readiness.figures.staffDeclaredExpensesPaise,25050);
    assert.equal(readiness.costCoverage.netDeliveredSaleLines,1);
    assert.equal(readiness.costCoverage.costedNetDeliveredSaleLines,1);
    assert.equal(readiness.costCoverage.complete,true);
    assert.equal(readiness.figures.grossProfitPaise,null); assert.equal(readiness.figures.operatingProfitPaise,null);
    const duplicate=await fetch(`${base}/v1/sync/events`,{method:'POST',headers:{'content-type':'application/json',authorization:`Bearer ${enrollment.token}`},body:JSON.stringify({shopId:enrollment.shopId,events:[event]})});
    assert.equal(duplicate.status,200); assert.equal((await duplicate.json()).results[0].status,'duplicate');
    const afterRetry=await fetch(`${base}/v1/dashboard/activity?shopId=${enrollment.shopId}&days=7`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal((await afterRetry.json()).totals.staffDeclaredExpensesPaise,25050,'an exact event retry must not double-count expense activity');
    const wrongShop=await fetch(`${base}/v1/dashboard/overview?shopId=${randomUUID()}`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(wrongShop.status,401);
    const wrongActivityShop=await fetch(`${base}/v1/dashboard/activity?shopId=${randomUUID()}&days=7`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(wrongActivityShop.status,401);
    const wrongCashflowShop=await fetch(`${base}/v1/dashboard/cashflow?shopId=${randomUUID()}&days=7`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(wrongCashflowShop.status,401);
    const wrongProfitShop=await fetch(`${base}/v1/dashboard/profit-readiness?shopId=${randomUUID()}&days=7`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(wrongProfitShop.status,401);
    const wrongCustomerShop=await fetch(`${base}/v1/dashboard/customers?shopId=${randomUUID()}`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(wrongCustomerShop.status,401);
    const logout=await fetch(`${base}/v1/logout`,{method:'POST',headers:{authorization:`Bearer ${enrollment.token}`}}); assert.equal(logout.status,200);
    const denied=await fetch(`${base}/v1/sync/events`,{method:'POST',headers:{'content-type':'application/json',authorization:`Bearer ${enrollment.token}`},body:JSON.stringify({shopId:enrollment.shopId,events:[{...event,eventId:randomUUID(),commandId:`retry-${randomUUID()}`,baseRevision:1}]})});
    assert.equal(denied.status,401);
    const deniedOverview=await fetch(`${base}/v1/dashboard/overview?shopId=${enrollment.shopId}`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(deniedOverview.status,401);
    const deniedCashflow=await fetch(`${base}/v1/dashboard/cashflow?shopId=${enrollment.shopId}&days=7`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(deniedCashflow.status,401);
    const deniedProfit=await fetch(`${base}/v1/dashboard/profit-readiness?shopId=${enrollment.shopId}&days=7`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(deniedProfit.status,401);
    const deniedCustomers=await fetch(`${base}/v1/dashboard/customers?shopId=${enrollment.shopId}`,{headers:{authorization:`Bearer ${enrollment.token}`}});
    assert.equal(deniedCustomers.status,401);
  } finally {
    await cleanupEnrollment(admin,enrollment);
    await new Promise((resolve)=>server.close(resolve)); await pool.end(); await admin.end();
  }
});
