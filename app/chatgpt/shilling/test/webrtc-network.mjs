// Diagnose native WebRTC independently of Shilling and its signaling server.
import { chromium } from '@playwright/test';
const browser = await chromium.launch({args:['--disable-features=WebRtcHideLocalIpsWithMdns','--allow-loopback-in-peer-connection']});
try {
  const a=await browser.newPage(), b=await browser.newPage();
  for(const page of [a,b]) {
    await page.context().grantPermissions(['microphone']);
    await page.goto('http://localhost:8081/api/config');
    await page.evaluate(()=>{
      window.pc=new RTCPeerConnection({iceServers:[]});
      window.result=new Promise(resolve=>window.done=resolve);
      pc.ondatachannel=e=>{e.channel.onmessage=m=>done(m.data);};
      window.gather=async()=>{
        if(pc.iceGatheringState!=='complete') await new Promise(resolve=>{
          pc.onicegatheringstatechange=()=>{if(pc.iceGatheringState==='complete')resolve();};
        });
        return pc.localDescription.toJSON();
      };
    });
  }
  const offer=await a.evaluate(async()=>{window.dc=pc.createDataChannel('test');dc.onopen=()=>dc.send('native-webrtc-ok');await pc.setLocalDescription(await pc.createOffer());return gather();});
  const answer=await b.evaluate(async offer=>{await pc.setRemoteDescription(offer);await pc.setLocalDescription(await pc.createAnswer());return gather();},offer);
  await a.evaluate(answer=>pc.setRemoteDescription(answer),answer);
  const result=await b.evaluate(()=>Promise.race([result,new Promise(resolve=>setTimeout(()=>resolve(pc.connectionState),10000))]));
  console.log('Native WebRTC:',result);
  if(result!=='native-webrtc-ok') {
    console.log('Local candidates:',answer.sdp.split('\r\n').filter(s=>s.startsWith('a=candidate:')));
    process.exitCode=1;
  }
} finally {await browser.close();}
