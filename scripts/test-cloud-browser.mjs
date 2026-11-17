// Real production Wasm UI + WebRTC against a running disposable cloud fixture.
// npm ci; SHILLING_TEST_WEB_URL=http://127.0.0.1:8138 SHILLING_TEST_SERVER_URL=http://127.0.0.1:8141 node scripts/test-cloud-browser.mjs
import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
const web = process.env.SHILLING_TEST_WEB_URL;
const server = process.env.SHILLING_TEST_SERVER_URL;
assert(web && server, 'Provide disposable test web and server URLs');
const artifacts = process.env.SHILLING_TEST_BROWSER_ARTIFACTS || `/tmp/shilling-browser-${Date.now()}`;
await mkdir(artifacts, {recursive:true});
const browser = await chromium.launch({channel:process.env.SHILLING_TEST_BROWSER_CHANNEL || 'chrome',headless:true});
const report=[];
const pageErrors=[];
const contexts=[];
async function poll(check, message, timeout=60000) {
 const end=Date.now()+timeout; let last;
 do {try {if(await check()) return;} catch(e){last=e;} await new Promise(r=>setTimeout(r,150));} while(Date.now()<end);
 throw new Error(`${message}${last ? ': '+last.message : ''}`);
}
async function click(page,name) {
 const button=page.getByRole('button',{name,exact:true});
 await button.waitFor({state:'attached'});
 await poll(()=>button.isEnabled(), `Button ${name} stayed disabled`);
 // Compose draws the interactive surface on canvas above its semantic DOM.
 await button.click({force:true});
}
async function fill(page,field,value) {
 await field.click({force:true});
 await page.locator('input, textarea').waitFor();
 await page.locator('input, textarea').fill(value);
 await page.keyboard.press('Tab');
 await poll(async()=> (await field.textContent()).includes(value),`Compose field did not commit ${value}`);
}
async function scroll(page,delta) {
 await page.mouse.move(1000,700);await page.mouse.wheel(0,delta);
 await page.waitForTimeout(200); // Allow canvas scroll/layout to catch up with its semantic DOM.
}
async function settings(page) {
 if(!page.url().endsWith('#/settings')) await click(page,'Settings');
 await poll(async()=>await page.getByText('Developer tools',{exact:true}).count()>0 || await page.getByText('Connect this device',{exact:true}).count()>0,'Settings did not load');
 await poll(async()=>(await page.getByRole('button',{name:'Recovery and devices',exact:true}).count()===1 || await page.getByText('Connect this device',{exact:true}).count()===1),'Cloud settings did not load');
}
async function cloudArea(page) {
 await settings(page);if(await page.getByText('Connect this device',{exact:true}).count()) return;await scroll(page,-3000);await scroll(page,660);
}
async function blankButton(page,index) {
 const buttons=page.getByRole('button',{name:'',exact:true});
 await poll(async()=>await buttons.count()>index,'Cloud toggle/radio missing');
 await buttons.nth(index).click({force:true});
 await page.waitForTimeout(200);
}
async function setup(page, household) {
 await page.goto(web);
 await click(page,'Self-hosted');
 await fill(page,page.getByRole('textbox').first(),server);
 await click(page,'Continue');
 await settings(page);await scroll(page,1500);
 const householdField=page.getByRole('textbox').filter({hasText:/^[0-9a-f-]{36}$/});
 await fill(page,householdField,household);
 await click(page,'Save household ID');
 await page.reload();
 await page.getByRole('button',{name:'Home',exact:true}).waitFor({timeout:60000});
 await cloudArea(page);
}
async function saveKey(page,household) {
 await page.getByText('Save your recovery key',{exact:true}).waitFor({state:'attached'});
 const key=await page.getByText(/^[A-Za-z0-9+/]{43}=$/).textContent();
 assert(key?.length===44);
 await writeFile(`${artifacts}/test-recovery-key`,key,{mode:0o600});
 await blankButton(page,0);
 await click(page,'Continue');
 await poll(async()=> (await (await fetch(`${server}/api/backup/policy`,{headers:{'X-Shilling-Household':household}})).json()).enabled,'Key setup did not enable cloud');
 await page.reload();await page.getByRole('button',{name:'Home',exact:true}).waitFor();
 return key;
}
async function importKey(page,key) {
 await page.getByRole('button',{name:'Use saved key',exact:true}).waitFor({state:'attached'});
 await fill(page,page.getByRole('textbox').nth(1),key);
 await click(page,'Use saved key');
 await page.getByText('Save your recovery key',{exact:true}).waitFor({state:'attached'});
 await click(page,'Continue');
 await page.waitForTimeout(1000);
 await page.reload();await page.getByRole('button',{name:'Home',exact:true}).waitFor();
}
async function consent(page,household) {
 await blankButton(page,3);
 await page.getByText('Allow Shilling to access your cloud data?',{exact:true}).waitFor({state:'attached'});
 await blankButton(page,0);
 await click(page,'Allow cloud access');
 await poll(async()=> (await (await fetch(`${server}/api/backup/policy`,{headers:{'X-Shilling-Household':household}})).json()).encryption==='TRUSTED_CLOUD','Consent did not complete');
 await page.waitForTimeout(500);await page.reload();await page.getByRole('button',{name:'Home',exact:true}).waitFor();
}
async function accounts(page) {
 await click(page,'Plan');await click(page,'Accounts');
 await page.getByRole('button',{name:'Add',exact:true}).waitFor({state:'attached'});
}
async function addAccount(page,name,balance) {
 await accounts(page);await click(page,'Add');
 await page.getByText('New account',{exact:true}).waitFor({state:'attached'});
 const fields=page.getByRole('textbox');
 await fill(page,fields.nth(0),name);await fill(page,fields.nth(1),String(balance));
 await click(page,'Save');
 await poll(async()=>await page.getByRole('button',{name:new RegExp(name)}).count()===1,'Account not saved');
}
async function row(page,name) {return await page.getByRole('button',{name:new RegExp(name)}).textContent();}
async function editAccount(page,name,balance) {
 await accounts(page);await page.getByRole('button',{name:new RegExp(name)}).click({force:true});
 await page.getByRole('button',{name:'Save',exact:true}).waitFor({state:'attached'});
 await fill(page,page.getByRole('textbox').nth(1),String(balance));await click(page,'Save');
 await poll(async()=> (await row(page,name)).includes(Number(balance).toFixed(2)),'Account edit not saved');
}
try {
 for(const mode of ['END_TO_END','TRUSTED_CLOUD']) {
  const household=randomUUID();const pages=[];const blocked=[0,0,0];
  for(let i=0;i<3;i++) {
   const context=await browser.newContext({viewport:{width:1352,height:900}});contexts.push(context);
   await context.routeWebSocket(url=>url.pathname==='/ws/signal',socket=>{blocked[i]++;socket.close();});
   await context.addInitScript(()=>{
    const Original=window.RTCPeerConnection;window.__cloudTestPeers=[];
    window.RTCPeerConnection=class extends Original {
     constructor(config,...rest){super({...config,iceTransportPolicy:'relay'},...rest);window.__cloudTestPeers.push(this);}
    };
   });
   const page=await context.newPage();page.setDefaultTimeout(20000);pages.push(page);
   page.on('pageerror',error=>pageErrors.push(`${mode} client ${i}: ${error.message}`));
   await setup(page,household);
  }
  console.log(`${mode}: three independent browser clients onboarded`);
  if(mode==='END_TO_END') {
   await blankButton(pages[0],0);const key=await saveKey(pages[0],household);
   console.log(`${mode}: source key created`);
   for(const page of pages.slice(1)) {await page.reload();await cloudArea(page);await importKey(page,key);console.log(`${mode}: receiving key imported`);}
  } else {
   for(const page of pages) {await page.reload();await cloudArea(page);await consent(page,household);}
   await pages[0].reload();await cloudArea(pages[0]);await blankButton(pages[0],0);
  }
  const shared='Shared cloud account';
  await addAccount(pages[0],shared,123.45);
  await Promise.all(pages.map(accounts));
  await poll(async()=> (await Promise.all(pages.map(p=>row(p,shared)))).every(r=>r.includes('123.45')),'Initial cloud fan-out failed',120000);
  const relays=await Promise.all(pages.map(p=>p.evaluate(async()=>{
   const selected=[];
   for(const peer of window.__cloudTestPeers){const stats=await peer.getStats();for(const pair of stats.values())if(pair.type==='candidate-pair'&&pair.state==='succeeded'&&pair.nominated)selected.push(stats.get(pair.localCandidateId)?.candidateType);}
   return selected;
  })));
  assert(relays.every(types=>types.includes('relay')),'Every browser must establish TURN relay');
  assert(blocked.every(n=>n>0),'Direct device sync signaling must be blocked');
  console.log(`${mode}: cloud-only fan-out and TURN verified`);
  await Promise.all(pages.map((p,i)=>addAccount(p,`Browser ${i} account`,10+i)));
  await Promise.all(pages.map(accounts));
  await poll(async()=> (await Promise.all(pages.flatMap(p=>[0,1,2].map(i=>row(p,`Browser ${i} account`))))).length===9,'Concurrent account convergence failed',120000);
  await Promise.all([editAccount(pages[1],shared,222.22),editAccount(pages[2],shared,333.33)]);
  await poll(async()=> {const rows=await Promise.all(pages.map(p=>row(p,shared)));return new Set(rows).size===1 && /222\.22|333\.33/.test(rows[0]);},'Conflicting edits did not converge',120000);
  await pages[0].goto('about:blank');
  await editAccount(pages[1],shared,444.44);
  await poll(async()=> (await row(pages[2],shared)).includes('444.44'),'Online peer did not catch up',120000);
  await pages[1].goto('about:blank');await pages[2].goto('about:blank');
  await pages[0].goto(web+'/#/plan');await accounts(pages[0]);
  await poll(async()=> (await row(pages[0],shared)).includes('444.44'),'Offline browser failed cloud-only recovery',120000);
  await pages[0].screenshot({path:`${artifacts}/${mode}-recovered.png`});
  assert.deepEqual(pageErrors, [], 'Unhandled browser application errors');
  report.push(`${mode}: three isolated production web clients, forced TURN, direct P2P disabled; initial fan-out, concurrent independent writes, conflicting edits, and offline recovery with other devices closed passed.`);
  console.log(report.at(-1));
  for(const page of pages) await page.context().close();
 }
} catch(error) {
 for(let i=0;i<contexts.length;i++) for(const page of contexts[i].pages()) {
  await page.screenshot({path:`${artifacts}/failure-${i}.png`}).catch(()=>{});
  await writeFile(`${artifacts}/failure-${i}.txt`,await page.locator('body').ariaSnapshot().catch(()=>''));
 }
 throw error;
} finally {
 await writeFile(`${artifacts}/report.txt`,report.join('\n'));await browser.close();console.log(`Browser artifacts: ${artifacts}`);
}
