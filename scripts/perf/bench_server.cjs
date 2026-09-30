// Local-only hosted-auth/signaling workload. Generates disposable RSA keys.
// Starts its own Supabase stub and server; refuses to reuse port 8081.
const http = require('node:http');
const net = require('node:net');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {spawn, execFileSync} = require('node:child_process');
const WebSocket = require('ws');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const type = kind => `finance.shilling.core.sync.SignalingMessage.${kind}`;
const sizes = (process.env.SIZES || '10,100,500').split(',').map(Number);
if (sizes.some(n => !Number.isInteger(n) || n < 1 || n > 500)) throw Error('SIZES must contain integers from 1 to 500');
const repeats = Number(process.env.REPEATS || 1);
if(!Number.isInteger(repeats) || repeats<1 || repeats>5) throw Error('REPEATS must be from 1 to 5');
const records = [];
const output = (workload, result) => { const row={workload,result}; records.push(row); console.log(JSON.stringify(row)); };
const stats = values => {
  values.sort((a,b) => a-b);
  const p = fraction => Number(values[Math.min(values.length-1,Math.floor(values.length*fraction))].toFixed(2));
  return {count:values.length,p50Ms:p(.5),p95Ms:p(.95),p99Ms:p(.99)};
};
let server, stub, logFd, sutPid, javaHome;
const sockets = new Set();
const agents = Array.from({length:500},()=>new http.Agent({keepAlive:true,maxSockets:1}));
const counters = {jwks:0,membership:0};
let membershipDelay = 25, jwksDelay = 200, membershipStatus = 200;
let key = crypto.generateKeyPairSync('rsa',{modulusLength:2048}), kid = 'synthetic-key-1';
let issuer;
function token(user) {
  const encode = obj => Buffer.from(JSON.stringify(obj)).toString('base64url');
  const body = `${encode({alg:'RS256',kid})}.${encode({sub:user,iss:issuer,aud:'authenticated',role:'authenticated',exp:Math.floor(Date.now()/1000)+3600})}`;
  return body+'.'+crypto.sign('RSA-SHA256',Buffer.from(body),key.privateKey).toString('base64url');
}
async function metadataBatch(name, count, deadline=15000) {
  const tokens=Array.from({length:count},(_,i)=>token(`user-h0-p${i}`));
  const before={...counters}, statuses={}, timings=[];
  await Promise.all(tokens.map(async (bearer,index) => {
    const start=performance.now();
    try {
      const response=await request('/api/household',agents[index],bearer,deadline);
      if(response.status===200 && JSON.parse(response.body).householdId!=='house-0') throw Error('Wrong household');
      statuses[response.status]=(statuses[response.status]||0)+1;
    } catch(error) {
      if(!error.code) throw error;
      statuses[error.code]=(statuses[error.code]||0)+1;
    }
    timings.push(performance.now()-start);
  }));
  output(name,{...stats(timings),statuses,jwksRequests:counters.jwks-before.jwks,membershipRequests:counters.membership-before.membership,membershipDelayMs:membershipDelay,jwksDelayMs:jwksDelay});
}
function request(route,agent,bearer,deadline=15000) {
  return new Promise((resolve,reject)=>{
    const req=http.get('http://127.0.0.1:8081'+route,{agent,headers:bearer?{Authorization:'Bearer '+bearer}:{}},res=>{
      let body='';res.setEncoding('utf8');res.on('data',chunk=>body+=chunk);res.on('end',()=>{clearTimeout(timer);resolve({status:res.statusCode,body})});
      res.on('error',reject);
    });
    const timer=setTimeout(()=>req.destroy(Object.assign(Error('HTTP deadline'),{code:'DEADLINE'})),deadline);
    req.on('error',error=>{clearTimeout(timer);reject(error)});
  });
}
let opening=0;const openWaiters=[];
async function openPeer(house, peer) {
  if(opening>=25) await new Promise(resolve=>openWaiters.push(resolve));
  opening++;
  const socket=new WebSocket('ws://127.0.0.1:8081/ws/signal'); sockets.add(socket);
  socket.on('close',()=>sockets.delete(socket));
  socket.on('error',()=>{});
  try {await new Promise((resolve,reject)=>{socket.once('open',resolve);socket.once('error',reject)})}
  finally {opening--;openWaiters.shift()?.()}
  socket.send(JSON.stringify({type:type('Join'),deviceId:peer,householdId:`house-${house}`,accessToken:token(`user-h${house}-p${peer}`)}));
  return socket;
}
function waitMessage(socket, predicate) {
  return new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{cleanup();reject(Error('Signaling response deadline'))},15000);
    const receive=data=>{const message=JSON.parse(data);if(predicate(message)){cleanup();resolve(message)}};
    const close=()=>{cleanup();reject(Error('Signaling socket closed before response'))};
    const cleanup=()=>{clearTimeout(timer);socket.off('message',receive);socket.off('close',close)};
    socket.on('message',receive);socket.on('close',close);
  });
}
async function signaling(householdCount, peersPerHouse=5) {
  const anchors=[];
  for(let house=0;house<householdCount;house++) {
    const anchor=await openPeer(house,'anchor'); anchors.push(anchor);
    anchor.on('message',data=>{
      const msg=JSON.parse(data);
      if(msg.type===type('Offer')) anchor.send(JSON.stringify({type:type('Answer'),fromDeviceId:'anchor',toDeviceId:msg.fromDeviceId,sdp:msg.sdp}));
    });
    // A second authenticated peer confirms the anchor is registered.
    const joined=waitMessage(anchor,msg=>msg.type===type('PeerList') && msg.deviceIds.includes('probe'));
    const probe=await openPeer(house,'probe'); await joined; probe.close();
  }
  const timings=[];
  await Promise.all(Array.from({length:householdCount*peersPerHouse},async(_,i)=>{
    const house=Math.floor(i/peersPerHouse), id=`burst-${i}`, start=performance.now();
    const socket=await openPeer(house,id);
    const reply=waitMessage(socket,msg=>msg.type===type('Answer') && msg.sdp===id);
    socket.send(JSON.stringify({type:type('Offer'),fromDeviceId:id,toDeviceId:'anchor',sdp:id}));
    await reply;timings.push(performance.now()-start);socket.close();
  }));
  anchors.forEach(socket=>socket.close());
  output(`authenticated_joins_${householdCount}_households`,{...stats(timings),households:householdCount,peersPerHouse,includes:'WebSocket open, authorization, Join and directed Offer/Answer echo'});
}
async function reconnects() {
  const timings=[];
  let replacedClosed=0;
  await Promise.all(Array.from({length:10},async(_,house)=>{
    const anchor=await openPeer(house,'reconnect-anchor');
    anchor.on('message',data=>{
      const msg=JSON.parse(data);
      if(msg.type===type('Offer')) anchor.send(JSON.stringify({type:type('Answer'),fromDeviceId:'reconnect-anchor',toDeviceId:msg.fromDeviceId,sdp:msg.sdp}));
    });
    const joined=waitMessage(anchor,msg=>msg.type===type('PeerList'));
    let previous=await openPeer(house,'reconnect-peer'); await joined;
    for(let round=0;round<3;round++) {
      const start=performance.now(), marker=`reconnect-${house}-${round}`;
      const current=await openPeer(house,'reconnect-peer');
      const reply=waitMessage(current,msg=>msg.type===type('Answer') && msg.sdp===marker);
      current.send(JSON.stringify({type:type('Offer'),fromDeviceId:'reconnect-peer',toDeviceId:'reconnect-anchor',sdp:marker}));
      await reply;
      timings.push(performance.now()-start);
      if(previous.readyState===WebSocket.CLOSED) replacedClosed++;
      // Closing the old connection must not unregister the replacement.
      const closed=previous.readyState===WebSocket.CLOSED ? Promise.resolve() : new Promise(resolve=>previous.once('close',resolve));
      previous.terminate();
      await closed;
      const afterClose=waitMessage(current,msg=>msg.type===type('Answer') && msg.sdp===marker+'-live');
      current.send(JSON.stringify({type:type('Offer'),fromDeviceId:'reconnect-peer',toDeviceId:'reconnect-anchor',sdp:marker+'-live'}));
      await afterClose;
      previous=current;
    }
    previous.close();anchor.close();
  }));
  output('authenticated_reconnects',{...stats(timings),households:10,rounds:3,replacedClosedBeforeReply:replacedClosed,replacementSurvivedOldClose:30});
}
function verifyFixedResults() {
  const failures=[];
  for(const {workload,result} of records) {
    if(result.statuses) {
      const expected=workload.startsWith('upstream_deadline_') || workload.startsWith('upstream_failure_') ? '503':'200';
      if(Object.keys(result.statuses).length!==1 || result.statuses[expected]!==result.count) failures.push(`${workload}: expected all ${expected}`);
    }
    if((workload.startsWith('cold_jwks_') || workload.startsWith('rotated_key_')) && result.jwksRequests!==1) failures.push(`${workload}: expected one JWKS fetch`);
    if(workload==='threads_during_slow_upstream' && result.blockingHttpStacks!==0) failures.push(`${workload}: blocking HTTP stacks remain`);
  }
  if(failures.length) throw Error('Fixed-server checks failed: '+failures.join('; '));
}
function findJava() {
  const rows=execFileSync('ps',['-axo','pid,ppid,comm'],{encoding:'utf8'}).trim().split('\n').slice(1).map(line=>line.trim().split(/\s+/,3));
  const owned=new Set([String(server.pid)]);
  for(let i=0;i<8;i++) for(const [pid,parent] of rows) if(owned.has(parent)) owned.add(pid);
  const children=rows.filter(([pid,parent,command])=>owned.has(pid) && command.endsWith('/java'));
  const target=children.find(([pid])=>!children.some(([,parent])=>parent===pid));
  if(!target) throw Error('Cannot identify owned server JVM');
  sutPid=Number(target[0]);javaHome=path.dirname(path.dirname(target[2]));
}
function threadSnapshot(workload) {
  const dump=execFileSync(path.join(javaHome,'bin/jcmd'),[String(sutPid),'Thread.print'],{encoding:'utf8',maxBuffer:16*1024*1024});
  output(workload,{threads:dump.split('\n').filter(line=>line.startsWith('"')).length,defaultDispatcherThreads:(dump.match(/^"DefaultDispatcher-worker-/gm)||[]).length,blockingHttpStacks:(dump.match(/HttpClientImpl\.send\(/g)||[]).length});
}
(async()=>{
  const portCheck=net.createServer();
  await new Promise((resolve,reject)=>{portCheck.once('error',reject);portCheck.listen(8081,'0.0.0.0',resolve)});
  await new Promise(resolve=>portCheck.close(resolve));
  stub=http.createServer(async(req,res)=>{
    const url=new URL(req.url,'http://localhost');
    let status=200,body;
    if(url.pathname==='/auth/v1/.well-known/jwks.json') {
      counters.jwks++;await delay(jwksDelay);
      body={keys:[{...key.publicKey.export({format:'jwk'}),kid,alg:'RS256',use:'sig'}]};
    } else if(url.pathname==='/rest/v1/user_profiles') {
      counters.membership++;await delay(membershipDelay);status=membershipStatus;
      const house=url.searchParams.get('user_id')?.match(/user-h(\d+)-/)?.[1];
      body=status===200 && house!==undefined ? [{household_id:`house-${house}`}]:[];
    } else { status=404;body={}; }
    res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(body));
  });
  await new Promise(resolve=>stub.listen(0,'127.0.0.1',resolve));
  const base=`http://127.0.0.1:${stub.address().port}`;issuer=base+'/auth/v1';
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'shilling-server-perf-'));logFd=fs.openSync(path.join(dir,'server.log'),'w');
  const env={...process.env,SHILLING_AUTH_MODE:'supabase',SHILLING_SUPABASE_URL:base,SHILLING_SUPABASE_ANON_KEY:'synthetic-publishable',SHILLING_SUPABASE_SERVICE_KEY:'synthetic-service-key'};
  delete env.SHILLING_SUPABASE_JWT_SECRET;delete env.SHILLING_TURN_SECRET;
  server=spawn('./kotlin',['run',...(process.env.SERVER_PROJECT_DIR?['--project-dir',process.env.SERVER_PROJECT_DIR]:[]),'--module','server','--jvm-args=-Xms128m -Xmx512m'],{env,detached:true,stdio:['ignore',logFd,logFd]});
  console.error('Local server log: '+path.join(dir,'server.log'));
  let ready=false;
  for(let i=0;i<240;i++) {
    if(server.exitCode!==null) throw Error('Server exited during startup');
    try {if((await fetch('http://127.0.0.1:8081/health',{signal:AbortSignal.timeout(500)})).ok){ready=true;break}}catch{}
    await delay(250);
  }
  if(!ready) throw Error('Server did not start within 60 seconds');
  findJava();
  // Pre-establish independent HTTP connections in small groups. Measure auth
  // concurrency separately from the host kernel's TCP accept backlog.
  for(let i=0;i<Math.max(...sizes);i+=25) await Promise.all(agents.slice(i,Math.min(i+25,Math.max(...sizes))).map(agent=>request('/health',agent)));
  await metadataBatch(`cold_jwks_${Math.max(...sizes)}`,Math.max(...sizes));
  for(let run=1;run<=repeats;run++) {
    for(const size of sizes) await metadataBatch(`warm_membership_${size}_run${run}`,size);
    for(const houses of [1,10,100]) await signaling(houses);
  }
  await reconnects();
  membershipDelay=1500;
  const slow=metadataBatch('slow_upstream_100',100);await delay(350);threadSnapshot('threads_during_slow_upstream');await slow;
  membershipDelay=7000;
  await metadataBatch('upstream_deadline_10',10,10000);
  membershipDelay=25;membershipStatus=503;
  await metadataBatch('upstream_failure_10',10);membershipStatus=200;
  key=crypto.generateKeyPairSync('rsa',{modulusLength:2048});kid='synthetic-key-2';
  await metadataBatch('rotated_key_100',100);
  if(process.env.VERIFY_FIXED==='1') verifyFixedResults();
  output('complete',{synthetic:true,transport:'control-plane HTTP metadata and WebSocket signaling only',fixedChecks:process.env.VERIFY_FIXED==='1',records:records.length});
})().catch(error=>{console.error(error);process.exitCode=1}).finally(async()=>{
  for(const socket of sockets) socket.terminate();
  agents.forEach(agent=>agent.destroy());
  if(server && server.exitCode===null) { try{process.kill(-server.pid,'SIGTERM')}catch{} }
  if(stub){stub.closeAllConnections();await new Promise(resolve=>stub.close(resolve))}
  if(logFd!==undefined) fs.closeSync(logFd);
});
