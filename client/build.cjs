const fs = require('node:fs');
const path = require('node:path');
const protobuf = require('protobufjs');
const source = fs.readFileSync(path.join(__dirname, '../protocol/chat.proto'), 'utf8');
protobuf.parse(source, {keepCase:true}); // Fail the build on an invalid schema.
const out = path.join(__dirname, 'generated');
fs.mkdirSync(out, {recursive:true});
fs.writeFileSync(path.join(out, 'protocol.js'), '// Generated from protocol/chat.proto. Do not edit.\nwindow.CHAT_PROTO_SCHEMA = ' + JSON.stringify(source) + ';\n');
fs.copyFileSync(require.resolve('protobufjs/dist/protobuf.min.js'), path.join(out, 'protobuf.min.js'));
fs.copyFileSync(require.resolve('protobufjs/LICENSE'), path.join(out, 'protobuf-LICENSE'));
console.log('Built local Protobuf runtime and canonical protocol schema.');
