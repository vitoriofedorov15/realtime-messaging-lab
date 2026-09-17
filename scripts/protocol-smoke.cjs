// Node >=22, npm ci --prefix client, and a built server JAR required.
const fs=require('node:fs');
const path=require('node:path');
const os=require('node:os');
const net=require('node:net');
const {spawn}=require('node:child_process');
const {createRequire}=require('node:module');
const assert=require('node:assert/strict');
const project=path.resolve(__dirname,'..');
const protobuf=createRequire(path.join(project,'client/package.json'))('protobufjs');
const ProtocolState=require('../client/src/protocol-state.js').default;
const schema=protobuf.parse(fs.readFileSync(path.join(project,'protocol/chat.proto'),'utf8'),{keepCase:true}).root;
const Request=schema.lookupType('chat.ClientRequest'), Response=schema.lookupType('chat.ServerResponse');
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
class Client {
  constructor() {
    this.ws=new WebSocket('ws://localhost:8080/ws'); this.ws.binaryType='arraybuffer';
    this.inbox=[]; this.public=[]; this.sizes=[]; this.state=new ProtocolState(); this.counter=0;
    this.ready=new Promise((resolve,reject)=>{this.ws.onopen=resolve; this.ws.onerror=reject;});
    this.ws.onmessage=event=>{
      try {
        this.sizes.push(event.data.byteLength);
        assert(event.data.byteLength<=1536*1024,'Serialized response exceeds limit');
        const response=Response.decode(new Uint8Array(event.data));
        assert.equal(response.protocol_version,2);
        if(response.join_success) this.state.start(response.join_success.snapshot_sequence,response.join_success.users);
        if(response.history_batch) this.public.push(...this.state.history(response.history_batch));
        if(response.broadcast && this.state.live(response.broadcast)) this.public.push(response.broadcast);
        this.inbox.push(response);
      } catch(error) {this.failure=error;}
    };
  }
  async wait(predicate) {
    for(let i=0;i<1000;i++) {
      if(this.failure) throw this.failure;
      const index=this.inbox.findIndex(predicate);
      if(index>=0) return this.inbox.splice(index,1)[0];
      await delay(10);
    }
    throw new Error('Timed out waiting for protocol response');
  }
  send(value) {
    const id='js_'+(++this.counter);
    this.ws.send(Request.encode(Request.create({protocol_version:2,request_id:id,...value})).finish());
    return id;
  }
  async join(name,avatar) {
    await this.ready;
    const id=this.send({type:0,join:{name,...(avatar ? {avatar,avatar_mime:'image/png'} : {})}});
    const start=await this.wait(r=>r.request_id===id && (r.join_success || r.error));
    assert(start.join_success,start.error?.error_message);
    await this.wait(r=>r.request_id===id && r.history_batch?.complete);
    return start.join_success;
  }
  async chat(text,image) {
    const id=this.send({type:1,chat:{text,...(image ? {image,image_mime:'image/png'} : {})}});
    const reply=await this.wait(r=>r.request_id===id);
    assert(reply.broadcast,reply.error?.error_message);
    return reply.broadcast;
  }
  close() {this.ws.close();}
}
(async()=>{
  await new Promise((resolve,reject)=>{const guard=net.createServer();guard.once('error',reject);guard.listen(8080,()=>guard.close(resolve));});
  const temp=fs.mkdtempSync(path.join(os.tmpdir(),'protocol-smoke-'));
  const log=fs.openSync(path.join(temp,'server.log'),'a');
  const java=process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME,'bin/java') : 'java';
  const server=spawn(java,['-jar',path.join(project,'server/target/chat-server-1.0-SNAPSHOT-jar-with-dependencies.jar')],{cwd:temp,stdio:['ignore',log,log]});
  const clients=[];
  try {
    let ready=false;
    for(let i=0;i<100;i++) {
      if(server.exitCode!==null) throw new Error('Server exited before startup');
      if(fs.readFileSync(path.join(temp,'server.log'),'utf8').includes('Chat Server started')) {ready=true;break;}
      await delay(100);
    }
    assert(ready,'Server started');
    const make=()=>{const client=new Client();clients.push(client);return client;};
    const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aF9sAAAAASUVORK5CYII=','base64');
    const avatar=Buffer.alloc(200*1024); png.copy(avatar);
    const image=Buffer.alloc(1024*1024); png.copy(image);
    const writer=make(); await writer.join('Writer',avatar);
    for(let i=0;i<4;i++) await writer.chat('image '+i,image);
    const reader=make(); const snapshot=await reader.join('Reader');
    assert.equal(reader.public.length,4);
    assert.equal(snapshot.snapshot_sequence.toString(),'4');
    assert.deepEqual([...snapshot.users],['Reader','Writer']);
    assert(reader.sizes.filter(n=>n>1024*1024).length>=4,'Large records arrive in separate byte-bounded batches');
    console.log('PASS: JavaScript/Java protocol, 4 MiB images plus avatars, bounded history, membership snapshot');
    const arrivals=[];
    const joining=Promise.all([0,1,2].map(async i=>{const c=make();arrivals.push(c);await c.join('Concurrent'+i);}));
    const writing=(async()=>{for(let i=0;i<40;i++) await writer.chat('live '+i);})();
    await Promise.all([joining,writing]);
    for(const c of [reader,...arrivals]) {
      for(let i=0;i<500 && c.public.length<44;i++) {if(c.failure)throw c.failure;await delay(10);}
      assert.equal(c.public.length,44);
      assert.equal(new Set(c.public.map(m=>m.message_id)).size,44);
      assert.deepEqual(c.public.map(m=>m.sequence.toString()),Array.from({length:44},(_,i)=>String(i+1)));
      assert(!c.failure);
    }
    console.log('PASS: 3 concurrent joins while sending 40 messages; each observer has sequences 1–44 exactly once');
    const privateId=writer.send({type:2,private_chat:{target_name:'Reader',text:'private contract'}});
    const sent=await writer.wait(r=>r.request_id===privateId);
    assert.equal(sent.private_msg.target_name,'Reader');
    const received=await reader.wait(r=>r.private_msg?.message_id===sent.private_msg.message_id);
    assert.equal(received.request_id,'');
    console.log('PASS: private recipient and stable message ID, sender correlation versus recipient event');
    const legacy=make();await legacy.ready;
    legacy.ws.send(Request.encode(Request.create({join:{name:'Legacy'},protocol_version:1,request_id:'old'})).finish());
    const error=await legacy.wait(r=>r.error);
    assert.equal(error.error.code,'UNSUPPORTED_VERSION');assert.equal(error.request_id,'old');
    console.log('PASS: incompatible protocol gets correlated UNSUPPORTED_VERSION');
    console.log('PROTOCOL SMOKE PASSED');
  } finally {
    for(const c of clients)c.close();
    if(server.exitCode===null) {
      const exited=new Promise(resolve=>server.once('exit',resolve));server.kill('SIGTERM');
      const timer=setTimeout(()=>server.kill('SIGKILL'),5000);await exited;clearTimeout(timer);
    }
    fs.closeSync(log);console.log('Synthetic data and server log:',temp);
  }
})().catch(error=>{console.error(error);process.exitCode=1;});
