const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const protobuf=require('protobufjs');
const ProtocolState=require('../protocol-state.js');
const msg=(id,seq)=>({message_id:id,sequence:String(seq)});
const batch=(entries,complete=false,watermark='2')=>({history:entries,complete,snapshot_sequence:watermark});
test('canonical schema produces the exact generated browser source',()=>{
  const context={window:{}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../generated/protocol.js'),'utf8'),context);
  assert.equal(context.window.CHAT_PROTO_SCHEMA,fs.readFileSync(path.join(__dirname,'../../protocol/chat.proto'),'utf8'));
  const root=protobuf.parse(context.window.CHAT_PROTO_SCHEMA,{keepCase:true}).root;
  const request=root.lookupType('chat.ClientRequest');
  const value={type:1,chat:{text:'hello'},protocol_version:2,request_id:'req_1'};
  const decoded=request.decode(request.encode(request.create(value)).finish());
  assert.equal(decoded.request_id,'req_1'); assert.equal(decoded.payload,'chat');
});
test('history batches deduplicate by ID and finish before live events',()=>{
  const state=new ProtocolState(); state.start('2',['Alice']);
  assert.equal(state.history(batch([msg('a',1)])).length,1);
  assert.equal(state.history(batch([msg('a',1),msg('b',2)])).length,1);
  state.history(batch([],true));
  assert.equal(state.live(msg('c',3)),true); assert.equal(state.live(msg('c',3)),false);
});
test('live before completion and sequence gaps are rejected',()=>{
  const state=new ProtocolState(); state.start('2',[]);
  assert.throws(()=>state.live(msg('c',3)));
  state.history(batch([],true));
  assert.throws(()=>state.live(msg('d',4)));
});
test('snapshot mismatch and out-of-order history are rejected',()=>{
  const state=new ProtocolState(); state.start('2',[]);
  assert.throws(()=>state.history(batch([],false,'3')));
  state.history(batch([msg('b',2)]));
  assert.throws(()=>state.history(batch([msg('a',1)])));
});
test('uint64 sequence never passes through unsafe floating point',()=>{
  const state=new ProtocolState(); const n='9007199254740993'; state.start(n,[]);
  state.history(batch([],true,n));
  assert.equal(state.live(msg('next','9007199254740994')),true);
});
test('reconnect resets snapshot, membership and IDs',()=>{
  const state=new ProtocolState(); state.start('1',['Old']); state.history(batch([msg('a',1)],true,'1'));
  state.start('1',['New']); assert.equal(state.ready,false); assert.deepEqual([...state.users],['New']);
  assert.equal(state.history(batch([msg('a',1)],true,'1')).length,1);
});
test('deduplication cache is bounded',()=>{
  const state=new ProtocolState(); for(let i=0;i<1100;i++) state.remember('id'+i);
  assert.equal(state.seen.size,1000);
});
