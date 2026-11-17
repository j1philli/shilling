// Isolated release web UI: startup, durable receipt import and actual rendered preview.
// Usage: node scripts/perf/profile_web_receipts.cjs [dist] [synthetic-file-directory]
const fs = require('node:fs'), path = require('node:path'), http = require('node:http');
const {chromium} = require('../ci/smoke/node_modules/playwright');
const {PNG, wsServer: WebSocketServer} = require('../ci/smoke/node_modules/playwright-core/lib/utilsBundle');
const root = path.resolve(process.argv[2] || 'web-app-dist');
const fixtures = path.resolve(process.argv[3] || '/tmp');
const label = process.env.LABEL || 'current';
const baselineDialog = process.env.BASELINE_DIALOG === '1';
const output = (workload, data) => console.log(JSON.stringify({label, workload, ...data}));
const headers = {'Cross-Origin-Opener-Policy':'same-origin', 'Cross-Origin-Embedder-Policy':'require-corp'};
const server = http.createServer((req,res) => {
  const uri = new URL(req.url,'http://localhost').pathname;
  if (uri === '/api/config') {res.writeHead(200,{...headers,'Content-Type':'application/json'});return res.end('{"authMode":"NONE"}');}
  if (uri === '/api/ice-servers') {res.writeHead(200,{...headers,'Content-Type':'application/json'});return res.end('{"iceServers":[],"ttlSeconds":3600}');}
  if (uri.startsWith('/api/')) {res.writeHead(200,{...headers,'Content-Type':'application/json'});return res.end('{}');}
  const file = path.resolve(root, '.' + (uri === '/' ? '/index.html' : uri));
  if (!file.startsWith(root + path.sep)) {res.writeHead(403);return res.end();}
  try {const bytes=fs.readFileSync(file);res.writeHead(200,{...headers,'Content-Type':file.endsWith('.wasm')?'application/wasm':/\.m?js$/.test(file)?'text/javascript':file.endsWith('.html')?'text/html':'application/octet-stream'});res.end(bytes);} catch {res.writeHead(404);res.end();}
});
const signaling = new WebSocketServer({server, path:'/ws/signal'});
signaling.on('connection', socket => socket.on('message', bytes => {
  const msg=JSON.parse(bytes);
  if(msg.type.endsWith('.Join')) socket.send(JSON.stringify({type:'finance.shilling.core.sync.SignalingMessage.PeerList',deviceIds:[]}));
}));
async function main() {
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  const browser=await chromium.launch({channel:'chrome',headless:true});
  try {
    const context=await browser.newContext({viewport:{width:1200,height:900}});
    await context.addInitScript(() => {
      window.SHILLING_SELF_HOSTED_ONLY=true;
      window.auditTasks=[];
      window.auditImageUrls=new Set();
      const create=URL.createObjectURL.bind(URL), revoke=URL.revokeObjectURL.bind(URL);
      URL.createObjectURL=blob=>{const url=create(blob);if(blob.type.startsWith('image/'))window.auditImageUrls.add(url);return url};
      URL.revokeObjectURL=url=>{window.auditImageUrls.delete(url);revoke(url)};
      new PerformanceObserver(list => window.auditTasks.push(...list.getEntries().map(e=>({start:e.startTime,duration:e.duration})))).observe({type:'longtask',buffered:true});
    });
    await context.route('**/*',r=>new URL(r.request().url()).hostname==='127.0.0.1'?r.continue():r.abort());
    const page=await context.newPage(), errors=[];
    page.on('pageerror',e=>{errors.push(e.message);console.error('PAGE ERROR',e.message)});
    const cdp=await context.newCDPSession(page);await cdp.send('Performance.enable');
    const click=name=>page.getByRole('button',{name,exact:true}).click({force:true,delay:80});
    async function metrics(start) {
      const [tasks,values]=await Promise.all([page.evaluate(s=>window.auditTasks.filter(e=>e.start>=s),start),cdp.send('Performance.getMetrics')]);
      return {longTasks:tasks.length,maxLongTaskMs:Math.max(0,...tasks.map(t=>t.duration)),totalLongTaskMs:tasks.reduce((n,t)=>n+t.duration,0),mainJsHeapBytes:values.metrics.find(v=>v.name==='JSHeapUsedSize').value};
    }
    const start=performance.now();
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    await page.getByRole('button',{name:'Continue',exact:true}).waitFor({timeout:60000});
    output('fresh_onboarding_ready',{elapsedMs:performance.now()-start,...await metrics(0)});
    await click('Continue'); await page.getByRole('button',{name:/^This week /}).waitFor({timeout:60000});
    const warm=performance.now();await page.reload();
    await page.getByRole('button',{name:/^This week /}).waitFor({timeout:60000});
    output('warm_home_ready',{elapsedMs:performance.now()-warm,...await metrics(0)});
    await click('Receipts'); await page.waitForTimeout(300);
    for(const mib of [10,50]) {
      if(mib===50 && baselineDialog) {await page.reload();await page.getByRole('button',{name:'Receipts',exact:true}).waitFor({timeout:60000});await click('Receipts');await page.waitForTimeout(250);}
      const name=`shilling-synthetic-${mib}MiB.png`;
      await click('Add');await page.waitForTimeout(200);
      if(!baselineDialog)await page.getByText('No file selected',{exact:true}).waitFor().catch(async error=>{
        console.error(await page.locator('body').ariaSnapshot());
        await page.screenshot({path:'/tmp/shilling-web-editor-failure.png'});
        throw error;
      });
      const [chooser]=await Promise.all([page.waitForEvent('filechooser'),click('Choose file')]);await chooser.setFiles(path.join(fixtures,name));
      await page.getByText(`${name} · ${mib}.0 MB`,{exact:true}).waitFor();await page.waitForTimeout(100);
      const saveStart=await page.evaluate(()=>performance.now());await click('Save');
      await page.getByRole('button',{name:'Choose file',exact:true}).waitFor({state:'hidden',timeout:120000});
      const sql=page.workers().find(w=>w.url().includes('sqldelight'));
      const lengths=await sql.evaluate(()=>db.exec('SELECT length(file_bytes) FROM receipt_files ORDER BY length(file_bytes)')[0].values.flat());
      if(!lengths.includes(mib*1024*1024))throw Error('Receipt bytes not stored');
      output('receipt_import',{mib,elapsedMs:await page.evaluate(s=>performance.now()-s,saveStart),...await metrics(saveStart)});
      await page.getByRole('button',{name:new RegExp('^'+name.replaceAll('.','\\.')+' Added ')}).click({force:true,delay:80});await page.waitForTimeout(200);
      for(let round=0;round<3;round++) {
        if(round>0) {
          await page.reload();await page.getByRole('button',{name:'Receipts',exact:true}).waitFor({timeout:60000});await click('Receipts');
          await page.getByRole('button',{name:new RegExp('^'+name.replaceAll('.','\\.')+' Added ')}).click({force:true,delay:80});await page.waitForTimeout(200);
        }
        const before=await page.evaluate(()=>performance.now());await click('Open receipt');
        await page.getByRole('img',{name,exact:true}).waitFor({timeout:60000});
        const deadline=Date.now()+60000;let rendered=false;
        while(Date.now()<deadline) {
          const png=PNG.sync.read(await page.screenshot({clip:{x:550,y:400,width:80,height:80}}));
          const colours=new Set();for(let i=0;i<png.data.length;i+=4)colours.add(`${png.data[i]},${png.data[i+1]},${png.data[i+2]}`);
          if(colours.size>300){rendered=true;break;}await page.waitForTimeout(50);
        }
        if(!rendered)throw Error('Image preview did not render');
        output('receipt_preview',{mib,round,elapsedMs:await page.evaluate(s=>performance.now()-s,before),...await metrics(before)});
        if(process.env.SCREENSHOT_DIR && round===0)await page.screenshot({path:path.join(process.env.SCREENSHOT_DIR,`shilling-${mib}MiB-preview.png`)});
        if(baselineDialog)await click('Close');
        else await page.getByRole('dialog').getByRole('button',{name:'Close',exact:true}).click();
        if(baselineDialog) {
          await page.waitForTimeout(500);
          output('baseline_dialog_accessibility',{mib,round,remainingButtons:await page.getByRole('button').count()});
          if(round===2) {await page.reload();await page.getByRole('button',{name:'Receipts',exact:true}).waitFor({timeout:60000});await click('Receipts');}
        } else {
          await page.getByRole('dialog').waitFor({state:'hidden'});
          await page.getByRole('button',{name:'Open receipt',exact:true}).waitFor();
          if(await page.evaluate(()=>window.auditImageUrls.size)!==0)throw Error('Preview object URL leaked');
        }
      }
      // Clear the selected editor before adding another receipt.
      if(!baselineDialog) {
        // Hover long enough to cover the web tooltip regression before closing the editor.
        await page.getByRole('button',{name:'Close',exact:true}).hover({force:true});
        await page.waitForTimeout(800);
        await click('Close');
      }
      await page.waitForTimeout(200);
    }
    await page.reload();await page.getByRole('button',{name:'Receipts',exact:true}).waitFor({timeout:60000});
    const sql=page.workers().find(w=>w.url().includes('sqldelight'));
    const lengths=await sql.evaluate(()=>db.exec('SELECT length(file_bytes) FROM receipt_files ORDER BY length(file_bytes)')[0].values.flat());
    if(JSON.stringify(lengths)!==JSON.stringify([10,50].map(n=>n*1024*1024)))throw Error('Durable reload lost receipt bytes');
    if(errors.length)throw Error(errors.join('\n'));
    output('complete',{durableReceiptBytes:lengths,pageErrors:errors});
  } finally {await browser.close();signaling.close();server.close();}
}
main().catch(error=>{console.error(error);process.exitCode=1;server.close();});
