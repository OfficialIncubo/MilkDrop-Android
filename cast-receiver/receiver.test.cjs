const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {records} = require('./stream.js');
function reader(chunks) { return {async read(){return chunks.length ? {value:chunks.shift(),done:false}:{done:true};}}; }
(async()=>{
 const payloads=[Uint8Array.from([1,2,3]),Uint8Array.from([4]),Uint8Array.from([5,6,7,8,9])];
 const wire=Buffer.concat(payloads.map(p=>{const h=Buffer.alloc(4);h.writeUInt32BE(p.length);return Buffer.concat([h,p]);}));
 for(let step=1;step<=wire.length;step++) {
  const chunks=[];for(let i=0;i<wire.length;i+=step)chunks.push(wire.subarray(i,i+step));
  const output=[];for await(const p of records(reader(chunks),100))output.push([...p]);
  assert.deepEqual(output,payloads.map(p=>[...p]));
 }
 async function invalid(bytes,limit=100) { let failed=false;try{for await(const p of records(reader([bytes]),limit)){} }catch{failed=true}assert(failed); }
 await invalid(Buffer.from([0,0,0,0]));await invalid(Buffer.from([0,0,1,0]));await invalid(wire.subarray(0,wire.length-1));await invalid(wire,0);
 console.log('PASS: arbitrary HTTP chunk boundaries, multiple records, empty/oversized/truncated records');
 let callback, count=0, stopped=false, canceled=false, revoked=0, timers=0, playResolve;
 const messages=[];
 let end=0;
 class Video extends EventTarget {
  constructor(){super();this.currentTime=0;this.playbackRate=1;this.buffered={get length(){return end?1:0},start(){return 0},end(){return end}};}
  set src(v){queueMicrotask(()=>latestMedia.dispatchEvent(new Event('sourceopen')))}
  play(){return new Promise(resolve=>{playResolve=resolve;})}
  pause(){stopped=true;}removeAttribute(){}load(){}
 }
 const video=new Video();let latestMedia;
 class MediaSource extends EventTarget {
  constructor(){super();latestMedia=this;this.readyState='open';}
  static isTypeSupported(){return true;}
  addSourceBuffer(){const target=new EventTarget();target.updating=false;target.appendBuffer=()=>{count++;end=count/60;queueMicrotask(()=>{target.dispatchEvent(new Event('updateend'));if(count>=6&&playResolve){video.dispatchEvent(new Event('playing'));playResolve();playResolve=null;}})};target.remove=()=>queueMicrotask(()=>target.dispatchEvent(new Event('updateend')));target.abort=()=>{};return target;}
  setLiveSeekableRange(){}
 }
 const samples=[];for(let i=0;i<30;i++){const h=Buffer.alloc(4);h.writeUInt32BE(1);samples.push(Buffer.concat([h,Buffer.from([1])]));}
 let pendingRead;
 const streamReader={async read(){if(samples.length)return {value:samples.shift(),done:false};return new Promise(resolve=>{pendingRead=resolve;});},async cancel(){canceled=true;if(pendingRead)pendingRead({done:true});}};
 let fullHdSupported=true;
 const context={canDisplayType:(type,codec,w,h,fps)=>{assert.equal(w,1920);assert.equal(h,1080);assert.equal(fps,60);return fullHdSupported;},sendCustomMessage(ns,sender,data){messages.push(data);},addCustomMessageListener(ns,fn){callback=fn;},addEventListener(){},start(){},getSenders(){return [1]},stop(){}};
 class TestURL extends URL {static createObjectURL(){return 'blob:test'}static revokeObjectURL(){revoked++}}
 const sandbox={cast:{framework:{CastReceiverContext:{getInstance:()=>context},system:{MessageType:{JSON:1},EventType:{SENDER_DISCONNECTED:1}}}},document:{getElementById:()=>video},window:{MediaSource,addEventListener(){}},MediaSource,URL:TestURL,AbortController,DOMException,MilkDropStream:{records},fetch:async()=>({ok:true,body:{getReader:()=>streamReader}}),performance,setInterval(){timers++;return 1;},clearInterval(){timers--;},console};
 vm.runInNewContext(fs.readFileSync(require('node:path').join(__dirname,'receiver.js'),'utf8'),sandbox);
 callback({senderId:'sender',data:{type:'HELLO'}});
 assert.equal(messages.at(-1).fullHd60,true);fullHdSupported=false;
 callback({senderId:'sender',data:{type:'HELLO'}});assert.equal(messages.at(-1).fullHd60,false);
 callback({senderId:'sender',data:{type:'START',version:1,url:'http://127.0.0.1:1234/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/frames',mime:'video/mp4; codecs="avc1.42c020"',maxRecordBytes:1024}});
 await new Promise(resolve=>setTimeout(resolve,50));
 assert.equal(count,30,'play() must not stall the append loop while the decoder needs input');
 assert(messages.some(m=>m.type==='PLAYING'));
 callback({senderId:'sender',data:{type:'STOP'}});
 await new Promise(resolve=>setTimeout(resolve,10));
 assert(canceled&&stopped&&revoked===1&&timers===0);
 assert(!messages.some(m=>m.type==='ERROR'),JSON.stringify(messages));
 console.log('PASS: custom namespace start, decoder startup, live-edge catch-up, stop and resource cleanup');
})().catch(e=>{console.error(e);process.exitCode=1;});
