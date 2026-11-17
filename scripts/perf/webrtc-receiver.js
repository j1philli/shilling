// Browser-side protocol fixture. Payload bytes exist only on the WebRTC channel.
(() => {
  const sig = name => `finance.shilling.core.sync.SignalingMessage.${name}`;
  const file = name => `finance.shilling.shared.data.sync.FileTransferMessage.${name}`;
  let pc, socket, channel, opening, active, ice = [], serial = Promise.resolve();
  const errors = [];
  const selfId = () => window.perf.selfId || 'a-browser';
  const peerId = () => window.perf.peerId || 'z-pixel';
  const sendSignal = (kind, fields) => socket.send(JSON.stringify({type: sig(kind), fromDeviceId: selfId(), toDeviceId: peerId(), ...fields}));
  function receive(event) {
    const processingStart = performance.now();
    if (event.data instanceof ArrayBuffer) {
      if (!active) return;
      const run = active;
      try {
        let bytes = new Uint8Array(event.data), index = run.seen.size;
        if (!run.rawChunks) {
          const frame = new DataView(event.data);
          if(bytes.length<12 || frame.getUint32(0)!==0x53484601) throw Error('Invalid receipt frame');
          const idSize=frame.getUint16(4);
          index=frame.getUint32(6);
          if(idSize<1 || idSize>1024 || 10+idSize>=bytes.length || index>=Math.ceil(run.expectedBytes/run.chunkSize)) throw Error('Invalid receipt frame bounds');
          if(new TextDecoder('utf-8',{fatal:true}).decode(bytes.subarray(10,10+idSize))!==run.id) throw Error('Wrong receipt frame ID');
          bytes=bytes.subarray(10+idSize);
        }
        const offset = index * run.chunkSize;
        run.binaryFrames++;
        if (bytes.length !== Math.min(run.chunkSize, run.expectedBytes-offset)) throw Error('Unexpected raw chunk size');
        for(let i=0;i<bytes.length;i++) if(bytes[i] !== (offset+i)%251) throw Error(`Binary mismatch at ${offset+i}`);
        if(!run.seen.has(index)) {run.seen.add(index); run.bytes += bytes.length;}
        if(run.interrupt && run.seen.size>=run.interrupt) {
          active=null; pc.close(); socket.close();
          run.resolve({status:'interrupted',bytes:run.bytes,milliseconds:performance.now()-run.start});
        }
      } catch(e) { active=null; run.reject(e); }
      finally { run.processingMs += performance.now()-processingStart; }
      return;
    }
    const msg = JSON.parse(event.data);
    if (!active || msg.receiptId !== active.id) return;
    const run = active;
    try {
      if (msg.type === file('FileHeader')) {
        if (msg.totalSize !== run.expectedBytes || msg.chunkCount !== Math.ceil(run.expectedBytes/run.chunkSize)) throw Error('Unexpected transfer header');
        run.size = msg.totalSize; run.seen = new Set(); run.bytes = 0;
      } else if (msg.type === file('FileComplete')) {
        if (run.bytes !== run.expectedBytes || run.seen?.size !== Math.ceil(run.expectedBytes/run.chunkSize)) throw Error(`Missing bytes: ${run.bytes}/${run.size}`);
        active = null;
        run.resolve({status:'complete', bytes:run.bytes, chunks:run.seen.size, binaryFrames:run.binaryFrames, receiverProcessingMs:run.processingMs, milliseconds:performance.now()-run.start});
      } else if (msg.type === file('FileNotAvailable')) throw Error('File unavailable');
      else throw Error('Unexpected receipt control message: '+msg.type);
    } catch (e) { active = null; run.reject(e); }
    finally { run.processingMs += performance.now() - processingStart; }
  }
  function attach(dc) {
    channel = dc; channel.binaryType='arraybuffer'; channel.onmessage = receive;
    channel.onopen = () => opening?.();
    channel.onerror = e => errors.push(String(e.error || 'data channel error'));
  }
  window.perf = {
    errors,
    async stats() {
      const stats = [...(await pc.getStats()).values()];
      const transport=stats.find(s=>s.type==='transport' && s.selectedCandidatePairId);
      const pair=stats.find(s=>s.id===transport?.selectedCandidatePairId) || stats.find(s=>s.type==='candidate-pair' && s.state==='succeeded' && s.nominated);
      const dc=stats.filter(s=>s.type==='data-channel');
      const local=stats.find(s=>s.id===pair?.localCandidateId), remote=stats.find(s=>s.id===pair?.remoteCandidateId);
      return {connectionState:pc.connectionState,channelState:channel.readyState,localAddress:local?.address,localPort:local?.port,remoteAddress:remote?.address,remotePort:remote?.port,rtt:pair?.currentRoundTripTime, availableOutgoingBitrate:pair?.availableOutgoingBitrate,
        bytesReceived:pair?.bytesReceived, bytesSent:pair?.bytesSent,
        channels:dc.map(s=>({label:s.label, messagesReceived:s.messagesReceived, bytesReceived:s.bytesReceived}))};
    },
    async connect() {
      if (pc) pc.close(); if (socket) socket.close();
      pc = new RTCPeerConnection({iceServers: []}); ice = []; serial = Promise.resolve();
      pc.onicecandidate = e => { if (e.candidate) sendSignal('IceCandidate', e.candidate.toJSON()); };
      pc.ondatachannel = e => attach(e.channel);
      if(window.perf.mediaBufferProbe) pc.addTransceiver('video',{direction:'recvonly'});
      attach(pc.createDataChannel('data'));
      const ready = new Promise((resolve,reject) => {
        const timeout = setTimeout(() => reject(Error('Data channel open timed out: '+pc.connectionState)), 30000);
        opening = () => {clearTimeout(timeout); resolve();};
      });
      socket = new WebSocket(window.perf.signalUrl || 'ws://127.0.0.1:8081/ws/signal');
      socket.onopen = () => socket.send(JSON.stringify({type:sig('Join'),deviceId:selfId(),householdId:'synthetic-live-perf',accessToken:null}));
      let offered = false;
      socket.onmessage = event => {
        serial = serial.then(async () => {
          const m = JSON.parse(event.data);
          if (m.fromDeviceId && m.fromDeviceId !== peerId()) return;
          if (m.type === sig('PeerList') && m.deviceIds.includes(peerId()) && !offered) {
            offered = true;
            await pc.setLocalDescription(await pc.createOffer());
            sendSignal('Offer',{sdp:pc.localDescription.sdp});
          } else if (m.type === sig('Answer')) {
            await pc.setRemoteDescription({type:'answer',sdp:m.sdp});
            for (const c of ice.splice(0)) await pc.addIceCandidate(c);
          } else if (m.type === sig('IceCandidate')) {
            const c = {candidate:m.candidate,sdpMid:m.sdpMid,sdpMLineIndex:m.sdpMLineIndex};
            if (pc.remoteDescription) await pc.addIceCandidate(c); else ice.push(c);
          }
        }).catch(e => errors.push(String(e)));
      };
      await ready;
      if(window.perf.connectSettleMs) await new Promise(r=>setTimeout(r,window.perf.connectSettleMs));
      const stats = [...(await pc.getStats()).values()];
      const pair = stats.find(s => s.type === 'candidate-pair' && s.state === 'succeeded' && s.nominated);
      const local = stats.find(s=>s.id===pair?.localCandidateId), remote=stats.find(s=>s.id===pair?.remoteCandidateId);
      return {connected:true,localCandidate:local?.candidateType,remoteCandidate:remote?.candidateType,protocol:local?.protocol, currentRoundTripTime:pair?.currentRoundTripTime};
    },
    request(mib, interrupt=0, id=`p2p-${mib}`, chunkSize=16384) {
      return new Promise((resolve,reject) => {
        const timeout = setTimeout(()=>{
          const state={connection:pc.connectionState,channel:channel.readyState,bytes:active?.bytes||0,chunks:active?.seen?.size||0,headerReceived:active?.size!=null};
          active=null;reject(Error('Receipt timed out: '+JSON.stringify(state)));
        },120000);
        active = {id, interrupt, chunkSize, rawChunks:!!window.perf.rawChunks, binaryFrames:0, expectedBytes:mib*1024*1024, processingMs:0, start:performance.now(),resolve:r=>{clearTimeout(timeout);resolve(r)},reject:e=>{clearTimeout(timeout);reject(e)}};
        channel.send(JSON.stringify({type:file('FileRequest'),receiptId:id}));
      });
    },
    async upload(mib, readback=true) {
      const started=performance.now();
      const id=selfId()==='a-browser'?'browser-upload':`${selfId()}-upload`,total=mib*1024*1024,count=Math.ceil(total/16384);
      const send = async message => {
        while(channel.bufferedAmount>256*1024) await new Promise(r=>setTimeout(r,5));
        channel.send(message instanceof Uint8Array ? message : JSON.stringify(message));
      };
      await send({type:file('FileHeader'),receiptId:id,totalSize:total,chunkCount:count,mimeType:null});
      for(let index=0;index<count;index++) {
        const offset=index*16384;
        const bytes=Uint8Array.from({length:Math.min(16384,total-offset)},(_,i)=>(offset+i)%251);
        const idBytes=new TextEncoder().encode(id), frame=new Uint8Array(10+idBytes.length+bytes.length), view=new DataView(frame.buffer);
        view.setUint32(0,0x53484601); view.setUint16(4,idBytes.length); view.setUint32(6,index);
        frame.set(idBytes,10);frame.set(bytes,10+idBytes.length);
        await send(frame);
      }
      await send({type:file('FileComplete'),receiptId:id});
      if(!readback) {
        while(channel.bufferedAmount>0) await new Promise(r=>setTimeout(r,5));
        await new Promise(r=>setTimeout(r,2000));
        return {status:'complete',bytes:total,chunks:count,binaryFrames:count,milliseconds:performance.now()-started};
      }
      return this.request(mib,0,id);
    }
  };
})();
