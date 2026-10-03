// Start the isolated Android P2pPerformanceActivity and the local signaling server first.
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {spawn,execFileSync}=require('node:child_process'),http=require('node:http'),WebSocket=require('ws');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
  if(['RAW','COMPARE','UPLOAD_ONLY','IDLE_ONLY','RECONNECTS','SOAK_ROUNDS'].filter(name=>process.env[name]).length>1)
    throw Error('Select only one RAW, COMPARE, UPLOAD_ONLY, IDLE_ONLY or RECONNECTS workload');
  if(process.env.RECONNECTS && (!Number.isInteger(Number(process.env.RECONNECTS)) || Number(process.env.RECONNECTS)<1 || Number(process.env.RECONNECTS)>100))
    throw Error('RECONNECTS requires an integer from 1 to 100');
  if(process.env.IDLE_ONLY && (!Number.isFinite(Number(process.env.IDLE_ONLY)) || Number(process.env.IDLE_ONLY)<1 || Number(process.env.IDLE_ONLY)>120))
    throw Error('IDLE_ONLY requires a duration of 1–120 seconds');
  if(process.env.PEER_ID && process.env.PEER_ID!=='z-pixel' && (process.env.RAW || process.env.COMPARE))
    throw Error('RAW and COMPARE control the Android fixture only');
  const receiveBuffer=process.env.RECEIVE_BUFFER_PROBE_BYTES;
  if(receiveBuffer && (!process.env.MEDIA_BUFFER_PROBE || !/^\d+$/.test(receiveBuffer) || Number(receiveBuffer)<10000 || Number(receiveBuffer)>10000000))
    throw Error('RECEIVE_BUFFER_PROBE_BYTES requires MEDIA_BUFFER_PROBE=1 and 10000–10000000 bytes');
  const profile=fs.mkdtempSync(path.join(os.tmpdir(),'shilling-webrtc-'));
  const server=http.createServer((req,res)=>{res.writeHead(200,{'Content-Type':'text/html'});res.end('<!doctype html><title>Synthetic WebRTC test</title>');});
  await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const chrome=spawn(process.env.CHROME_BIN||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',[
    '--headless=new','--no-first-run','--no-default-browser-check','--remote-debugging-port=0',`--user-data-dir=${profile}`,
    ...(receiveBuffer ? [`--force-fieldtrials=WebRTC-ReceiveBufferSize/size_bytes:${receiveBuffer}/`] : []),
    '--disable-features=WebRtcHideLocalIpsWithMdns',`http://127.0.0.1:${server.address().port}`
  ],{stdio:'ignore'});
  let ws,capture;
  try {
    let port;
    for(let i=0;i<100;i++){try{port=fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0];break;}catch{await delay(100)}}
    if(!port)throw Error('Chrome failed to start');
    const targets=await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
    ws=new WebSocket(targets.find(t=>t.type==='page').webSocketDebuggerUrl);
    await new Promise((r,j)=>{ws.once('open',r);ws.once('error',j)});
    let id=0;const pending=new Map();
    ws.on('message',raw=>{const m=JSON.parse(raw);if(m.id&&pending.has(m.id)){const p=pending.get(m.id);pending.delete(m.id);m.error?p.reject(Error(JSON.stringify(m.error))):p.resolve(m.result)}});
    const command=(method,params={})=>new Promise((resolve,reject)=>{const key=++id;pending.set(key,{resolve,reject});ws.send(JSON.stringify({id:key,method,params}))});
    const evaluate=async expression=>{const r=await command('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value};
    await evaluate(fs.readFileSync(path.join(__dirname,'webrtc-receiver.js'),'utf8'));
    if(process.env.SIGNAL_URL) {
      const signalUrl=new URL(process.env.SIGNAL_URL);
      if(!['ws:','wss:'].includes(signalUrl.protocol)) throw Error('SIGNAL_URL must use ws or wss');
      await evaluate(`perf.signalUrl=${JSON.stringify(signalUrl.href)}`);
    }
    if(process.env.BROWSER_ID) {
      if(!['a-browser','b-browser'].includes(process.env.BROWSER_ID)) throw Error('Use synthetic browser IDs');
      await evaluate(`perf.selfId=${JSON.stringify(process.env.BROWSER_ID)}`);
    }
    if(process.env.PEER_ID) await evaluate(`perf.peerId=${JSON.stringify(process.env.PEER_ID)}`);
    if(process.env.CONNECT_SETTLE_MS) {
      const ms=Number(process.env.CONNECT_SETTLE_MS);
      if(!Number.isFinite(ms)||ms<0||ms>5000) throw Error('CONNECT_SETTLE_MS requires 0–5000 milliseconds');
      await evaluate(`perf.connectSettleMs=${ms}`);
    }
    const output=async(name,expression)=>{const result=await evaluate(expression); const expected=name.startsWith('interrupt_')?'interrupted':(name.startsWith('receive_')||name.startsWith('retry_')||name.startsWith('upload_'))?'complete':null; if(expected && result.status!==expected)throw Error(`${name}: expected ${expected}, got ${result.status}`); console.log(JSON.stringify({workload:name,result}));};
    if(process.env.MEDIA_BUFFER_PROBE) await evaluate('perf.mediaBufferProbe=true');
    if(process.env.RAW) await evaluate('perf.rawChunks=true');
    await output('connect','perf.connect()');
    if(process.env.SOAK_ROUNDS) {
      const rounds=Number(process.env.SOAK_ROUNDS);
      if(!Number.isInteger(rounds)||rounds<1||rounds>100) throw Error('SOAK_ROUNDS must be 1–100');
      await command('Performance.enable');
      for(let round=0;round<rounds;round++) {
        await output(`receive_50MiB_round${round}`, 'perf.request(50)');
        await output(`upload_10MiB_round${round}`, 'perf.upload(10)');
        if(round%3===2) {
          await output(`interrupt_50MiB_round${round}`, 'perf.request(50,64)');
          await output(`reconnect_round${round}`, 'perf.connect()');
          await output(`retry_50MiB_round${round}`, 'perf.request(50)');
        }
        console.log(JSON.stringify({workload:'soak_sample',round,wallTimeMs:Date.now(),metrics:await command('Performance.getMetrics'),route:await evaluate('perf.stats()')}));
        await delay(10000);
      }
      await output('browser_errors','perf.errors');
      if((await evaluate('perf.errors')).length) throw Error('Browser reported protocol errors');
      return;
    }
    if(process.env.IDLE_ONLY) {
      await delay(Number(process.env.IDLE_ONLY)*1000);
      await output('browser_errors','perf.errors');
      return;
    }
    if(process.env.RECONNECTS) {
      for(let run=1;run<=Number(process.env.RECONNECTS);run++) {
        if(run>1) await output(`reconnect_${run}`,'perf.connect()');
        await output(`receive_1MiB_connection${run}`,'perf.request(1)');
      }
      await output('browser_errors','perf.errors');
      return;
    }
    for(let i=0;i<40;i++){if((await evaluate('perf.stats()')).localPort)break;await delay(50);}
    if(process.env.ROUTE_FILE) fs.writeFileSync(process.env.ROUTE_FILE,JSON.stringify(await evaluate('perf.stats()')));
    if(process.env.CAPTURE) {
      const route=await evaluate('perf.stats()');
      if(!route.localPort || !route.remotePort || !/^\d+\.\d+\.\d+\.\d+$/.test(route.remoteAddress)) throw Error('IPv4 route required for packet capture');
      const filter=`udp and host ${route.remoteAddress} and port ${route.localPort} and port ${route.remotePort}`;
      capture=spawn('/usr/sbin/tcpdump',['-i','en0','-s','128','-U','-w',process.env.CAPTURE,filter],{stdio:['ignore','ignore','pipe']});
      capture.stderr.on('data',b=>process.stderr.write(b));
      await delay(300);
    }
    const udpCounters=()=>{
      const s=execFileSync('netstat',['-s','-p','udp'],{encoding:'utf8'});
      return {received:Number(s.match(/(\d+) datagrams received/)[1]),fullSocketDrops:Number(s.match(/(\d+) dropped due to full socket buffers/)[1])};
    };
    const receive=async(name,expression)=>{
      const before=process.env.SOCKET_DIAG?udpCounters():null;
      await output(name,expression);
      if(before){const after=udpCounters();console.log(JSON.stringify({workload:`udp_counters_${name}`,received:after.received-before.received,fullSocketDrops:after.fullSocketDrops-before.fullSocketDrops}));}
    };
    if(process.env.SOCKET_DIAG) {
      const route=await evaluate('perf.stats()');
      const socket=execFileSync('netstat',['-anv','-f','inet'],{encoding:'utf8'}).split('\n').filter(l=>l.startsWith('udp') && l.includes(`.${route.localPort} `));
      console.log(JSON.stringify({workload:'socket',socket}));
      const before=udpCounters();await delay(2000);const after=udpCounters();
      console.log(JSON.stringify({workload:'idle_udp_counters',received:after.received-before.received,fullSocketDrops:after.fullSocketDrops-before.fullSocketDrops}));
    }
    if(process.env.UPLOAD_ONLY) {
      const mib=Number(process.env.UPLOAD_ONLY);
      if(![1,10,50].includes(mib)) throw Error('UPLOAD_ONLY requires 1, 10 or 50 MiB');
      if(process.env.UPLOAD_NO_READBACK==='1')
        await output(`upload_only_${mib}MiB`,`perf.upload(${mib},false)`);
      else await output(`upload_and_readback_${mib}MiB`,`perf.upload(${mib})`);
      await output('browser_errors','perf.errors');
      return;
    }
    if(process.env.RAW) {
      const variants = process.env.RAW.split(',');
      for(let run=1;run<=Number(process.env.REPEATS||1);run++) for(const variant of variants) {
        const [mode,size='16384',poll='10',cap='262144']=variant.split(':');
        execFileSync('adb',['shell','am','start','-f','0x20000000','-n','finance.shilling.perf/.P2pPerformanceActivity',
          '--es','raw',mode,'--ei','chunkSize',size,'--el','pollMs',poll,'--ei','bufferCap',cap],{stdio:'ignore'});
        await receive(`receive_raw_${variant}_run${run}`,`perf.request(${Number(process.env.RAW_MIB||50)},0,'p2p-${Number(process.env.RAW_MIB||50)}',${Number(size)})`);
        await output(`stats_${variant}_run${run}`,'perf.stats()');
      }
      return;
    }
    if(process.env.COMPARE) {
      for(let pair=1;pair<=3;pair++) for(const pipeline of (pair%2 ? [false,true] : [true,false])) {
        execFileSync('adb',['shell','am','start','-f','0x20000000','-n','finance.shilling.perf/.P2pPerformanceActivity','--ez','pipeline',String(pipeline)],{stdio:'ignore'});
        await receive(`receive_50MiB_${pipeline?'pipeline':'sequential'}_pair${pair}`,'perf.request(50)');
      }
      return;
    }
    const sizes=process.env.SIZES ? process.env.SIZES.split(',').map(Number) : (process.env.SLOW_ONLY ? [1] : [1,10,50]);
    for(let run=1;run<=Number(process.env.REPEATS||1);run++)
      for(const size of sizes)await receive(`receive_${size}MiB_run${run}`,`perf.request(${size})`);
    if(process.env.FAST_ONLY) return;
    await command('Network.enable');
    await command('Network.emulateNetworkConditionsByRule',{offline:false,matchedNetworkConditions:[{urlPattern:'',latency:100,downloadThroughput:128*1024,uploadThroughput:128*1024,packetLoss:1,packetQueueLength:50}]});
    await output('reconnect_with_emulation','perf.connect()');
    await output('receive_1MiB_emulated_slow_link','perf.request(1)');
    await command('Network.emulateNetworkConditionsByRule',{offline:false,matchedNetworkConditions:[]});
    if(process.env.SLOW_ONLY) return;
    await output('reconnect_without_emulation','perf.connect()');
    await output('interrupt_50MiB','perf.request(50,64)');
    await delay(1000);
    await output('reconnect','perf.connect()');
    await output('retry_50MiB','perf.request(50)');
    await output('upload_and_readback_1MiB','perf.upload(1)');
    await output('browser_errors','perf.errors');
  } finally {
    if(capture && capture.exitCode===null) {capture.kill('SIGINT');await new Promise(r=>capture.once('close',r));}
    ws?.close();chrome.kill();server.close();
  }
})().catch(e=>{console.error(e);process.exitCode=1});
