// Production Ktor device-removal route with disposable auth/registry services.
// No entity payloads; run the isolated Android/native Settings screen against it.
const http=require('node:http'),net=require('node:net'),crypto=require('node:crypto'),fs=require('node:fs');
const {spawn,execFileSync}=require('node:child_process');
const {WebSocket}=require('ws');
const port=Number(process.env.PORT||18085),house='synthetic-live-perf';
const prefix='finance.shilling.core.sync.SignalingMessage.';
const key=crypto.generateKeyPairSync('rsa',{modulusLength:2048});
const devices=new Map(['synthetic-device','synthetic-peer'].map(id=>[id,{device_id:id,owner_user_id:'synthetic-user',registered_at:'2026-10-01',last_seen_at:null}]));
const tokenPath=process.env.TOKEN_FILE||'/tmp/shilling-hosted-ui-token';
const requests=[];let child,stub,issuer;const sockets=[];
let removed=false,closed=false,hint=false,complete=false;
const report=value=>console.log(JSON.stringify(value));
function check(){if(removed&&closed&&hint&&!complete){complete=true;report({event:'remove-verified',registryRemoved:true,peerPolicyClosed:true,survivorReceivedRemoval:true,requests});}}
function token(){const e=x=>Buffer.from(JSON.stringify(x)).toString('base64url');const body=e({alg:'RS256',kid:'synthetic'})+'.'+e({sub:'synthetic-user',iss:issuer,aud:'authenticated',role:'authenticated',exp:Math.floor(Date.now()/1000)+3600});return body+'.'+crypto.sign('RSA-SHA256',Buffer.from(body),key.privateKey).toString('base64url');}
function cleanup(){sockets.forEach(s=>s.terminate());if(child)try{process.kill(-child.pid,'SIGTERM')}catch{};stub?.close();try{fs.unlinkSync(tokenPath)}catch{}}
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>{cleanup();process.exit(complete?0:1)});
(async()=>{
 const probe=net.createServer();await new Promise((r,j)=>{probe.once('error',j);probe.listen(port,'0.0.0.0',r)});await new Promise(r=>probe.close(r));
 stub=http.createServer(async(req,res)=>{
  const url=new URL(req.url,'http://localhost');let body={},status=200;
  if(url.pathname==='/auth/v1/.well-known/jwks.json')body={keys:[{...key.publicKey.export({format:'jwk'}),kid:'synthetic',alg:'RS256',use:'sig'}]};
  else if(url.pathname==='/rest/v1/user_profiles')body=[{household_id:house}];
  else if(url.pathname==='/rest/v1/hosted_space_memberships')body=[{role:'owner'}];
  else if(url.pathname==='/rest/v1/rpc/manage_hosted_space')body={activeSpaceId:house,spaces:[{id:house,name:'Synthetic Home',kind:'home',role:'owner'}],members:[{userId:'synthetic-user',email:null,role:'owner'}],requiresSpaceSelection:false};
  else if(url.pathname==='/rest/v1/rpc/register_hosted_device'){
   let raw='';for await(const b of req)raw+=b;const input=JSON.parse(raw);
   body=devices.has(input.p_device_id);
  }else if(url.pathname==='/rest/v1/hosted_devices'){
   if(req.method==='DELETE'){
    const id=url.searchParams.get('device_id')?.replace(/^eq\./,'');
    if(id!=='synthetic-peer'){status=400;body={};}else{devices.delete(id);removed=true;status=204;check();}
   }else body=[...devices.values()];
   requests.push({method:req.method,count:devices.size});
  }else{status=404;body={};}
  res.writeHead(status,{'Content-Type':'application/json'});res.end(status===204?'':JSON.stringify(body));
 });await new Promise(r=>stub.listen(0,'127.0.0.1',r));
 const base=`http://127.0.0.1:${stub.address().port}`;issuer=base+'/auth/v1';
 const env={...process.env,SHILLING_PORT:String(port),SHILLING_AUTH_MODE:'supabase',SHILLING_SUPABASE_URL:base,SHILLING_SUPABASE_ANON_KEY:'synthetic-publishable',SHILLING_SUPABASE_SERVICE_KEY:'synthetic-service'};
 for(const k of Object.keys(env))if(/TURN_SECRET|JWT_SECRET|REVENUECAT/.test(k))delete env[k];
 const log=fs.openSync('/tmp/shilling-hosted-ui-server.log','w');
 child=spawn('./kotlin',['run','--module','server'],{env,detached:true,stdio:['ignore',log,log]});fs.closeSync(log);
 let ready=false;for(let i=0;i<240;i++){
  if(child.exitCode!==null)throw Error('Server exited');
  try{if((await fetch(`http://127.0.0.1:${port}/health`,{signal:AbortSignal.timeout(500)})).ok){ready=true;break}}catch{}
  await new Promise(r=>setTimeout(r,250));
 }if(!ready)throw Error('Server startup deadline');
 const bearer=token();fs.writeFileSync(tokenPath,bearer,{mode:0o600});
 for(const id of ['synthetic-device','synthetic-peer']){
  const socket=new WebSocket(`ws://127.0.0.1:${port}/ws/signal`);sockets.push(socket);
  socket.on('message',bytes=>{const msg=JSON.parse(bytes);if(id==='synthetic-device'&&msg.removedDeviceIds?.includes('synthetic-peer')){hint=true;check();}});
  socket.on('close',code=>{if(id==='synthetic-peer'){closed=code===1008;check();}});
  await new Promise((r,j)=>{socket.once('open',r);socket.once('error',j)});
  socket.send(JSON.stringify({type:prefix+'Join',deviceId:id,householdId:house,accessToken:bearer}));
  await new Promise(r=>setTimeout(r,150));
 }
 report({event:'ready',port,tokenFile:tokenPath,syntheticDevices:devices.size});
 if(process.argv.includes('--launch-android')){
  const adb=process.env.ANDROID_SERIAL?['-s',process.env.ANDROID_SERIAL]:[];
  execFileSync('adb',[...adb,'reverse',`tcp:${port}`,`tcp:${port}`]);
  execFileSync('adb',[...adb,'shell','am','start','-S','-W','-n','finance.shilling.perf/.HostedUiPerformanceActivity','--es','server',`http://127.0.0.1:${port}`,'--es','token',bearer],{stdio:'ignore'});
  report({event:'android-settings-launched'});
 }
})().catch(e=>{console.error(e);cleanup();process.exitCode=1});
