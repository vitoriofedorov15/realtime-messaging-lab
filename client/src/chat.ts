import { useCallback, useEffect, useRef, useState } from 'react';
import protobuf from 'protobufjs';
import schema from '../../protocol/chat.proto?raw';
import ProtocolState from './protocol-state.js';

export type Attachment = { bytes: Uint8Array; mime: string; url: string; name: string };
export type ChatMessage = { id: string; sender: string; target?: string; text: string; time: number; image?: string; avatar?: string; status?: 'sending' | 'failed' | 'unknown'; };
export type Status = 'demo' | 'connecting' | 'syncing' | 'connected' | 'reconnecting' | 'disconnected';
const root = protobuf.parse(schema, { keepCase: true }).root;
const Request = root.lookupType('chat.ClientRequest');
const Response = root.lookupType('chat.ServerResponse');
const palette = ['sage', 'peach', 'lavender', 'sand', 'blue'];
export const color = (name: string) => palette[Array.from(name).reduce((n, c) => n + c.charCodeAt(0), 0) % palette.length];
export const initials = (name: string) => name.slice(0, 2).toUpperCase();
export const validName = (name: string) => /^[\p{L}\p{Nd}_-]+$/u.test(name) && Array.from(name).length <= 50;
export function dataUrl(bytes: Uint8Array | undefined, mime: string) {
  if (!bytes?.length || !['image/png', 'image/jpeg'].includes(mime)) return undefined;
  let binary = ''; for (const byte of bytes) binary += String.fromCharCode(byte);
  return `data:${mime};base64,${btoa(binary)}`;
}
export async function readImage(file: File, avatar = false): Promise<Attachment> {
  if (!['image/png', 'image/jpeg'].includes(file.type)) throw new Error('Choose a PNG or JPEG image.');
  if (!file.size || file.size > (avatar ? 200 * 1024 : 1024 * 1024)) throw new Error(avatar ? 'Keep your avatar under 200 KiB.' : 'Keep your image under 1 MiB.');
  const bytes = new Uint8Array(await file.arrayBuffer());
  const url = dataUrl(bytes, file.type)!;
  await new Promise<void>((resolve, reject) => {
    const img = new Image(); img.onload = () => img.width > 4096 || img.height > 4096 || img.width * img.height > 16_000_000 ? reject(new Error('Image dimensions are too large.')) : resolve();
    img.onerror = () => reject(new Error('This image could not be read.')); img.src = url;
  });
  return { bytes, mime: file.type, url, name: file.name };
}
const now = Date.now();
export const demoMessages: ChatMessage[] = [
  { id: 'd1', sender: 'Maya', text: 'A fresh little space 🌿', time: now - 360000 },
  { id: 'd2', sender: 'Theo', text: 'Love the quiet corners.', time: now - 300000 },
  { id: 'd3', sender: 'Maya', text: 'Today’s inspiration.', time: now - 240000, image: '/inspiration.svg' },
  { id: 'd4', sender: 'You', text: 'Less noise. More ideas.', time: now - 180000 },
  { id: 'd5', sender: 'Lena', text: 'Room for good things ✨', time: now - 120000 },
];
// SVG is bundled artwork used only by the clearly labelled demo, not an accepted upload format.
export function useChat() {
  const [messages, setMessages] = useState<ChatMessage[]>(demoMessages);
  const [users, setUsers] = useState(['You', 'Maya', 'Theo', 'Lena']);
  const [name, setName] = useState('You');
  const [avatar, setAvatar] = useState<string>();
  const [status, setStatus] = useState<Status>('demo');
  const [error, setError] = useState('');
  const [countdown, setCountdown] = useState(0);
  const socket = useRef<WebSocket | null>(null);
  const timer = useRef<ReturnType<typeof setInterval> | undefined>(undefined);
  const config = useRef<{ name: string; avatar?: Attachment } | undefined>(undefined);
  const protocol = useRef(new ProtocolState());
  const joinId = useRef('');
  const pending = useRef(new Set<string>());
  const desired = useRef(false);
  const openRef = useRef<() => void>(() => {});
  const clearTimer = () => { if (timer.current) clearInterval(timer.current); timer.current = undefined; };
  const stop = useCallback(() => {
    desired.current = false; clearTimer();
    const ws = socket.current; socket.current = null; ws?.close();
    pending.current.clear();
  }, []);
  useEffect(() => () => stop(), [stop]);
  const append = (message: ChatMessage, requestId?: string) => setMessages(previous => {
    const retained = previous.filter(m => m.id !== requestId && m.id !== message.id);
    return [...retained, message].slice(-500);
  });
  const convert = (m: any): ChatMessage => ({ id: m.message_id, sender: m.sender_name, target: m.target_name || undefined, text: m.text || '', time: Number(m.timestamp), image: dataUrl(m.image, m.image_mime), avatar: dataUrl(m.sender_avatar, m.sender_avatar_mime) });
  const open = useCallback(() => {
    if (!config.current || !desired.current) return;
    clearTimer(); setCountdown(0); setStatus('connecting');
    const endpoint = import.meta.env.VITE_WS_URL || (location.host ? `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws` : 'ws://localhost:8080/ws');
    let ws: WebSocket;
    try { ws = new WebSocket(endpoint); } catch { setStatus('disconnected'); setError('The server address is invalid.'); return; }
    socket.current = ws; ws.binaryType = 'arraybuffer';
    ws.onopen = () => {
      if (socket.current !== ws) return;
      const c = config.current!; joinId.current = crypto.randomUUID();
      ws.send(Request.encode(Request.create({ protocol_version: 2, request_id: joinId.current, type: 0, join: { name: c.name, avatar: c.avatar?.bytes, avatar_mime: c.avatar?.mime || '' } })).finish());
    };
    ws.onmessage = event => {
      if (socket.current !== ws) return;
      try {
        const r: any = Response.decode(new Uint8Array(event.data));
        if (r.protocol_version !== 2) throw new Error('The server uses an incompatible protocol.');
        if (r.error) {
          const id = r.request_id;
          setMessages(previous => previous.map(m => m.id === id ? { ...m, status: 'failed' } : m)); pending.current.delete(id);
          setError(r.error.error_message);
          if (r.type === 1 || r.error.code === 'UNSUPPORTED_VERSION' || id === joinId.current) { desired.current = false; setStatus('disconnected'); ws.close(); }
          return;
        }
        if (r.join_success) {
          if (r.request_id !== joinId.current) throw new Error('Unexpected join response.');
          const p = r.join_success; protocol.current.start(p.snapshot_sequence, p.users || []); setUsers(p.users || []);
          setMessages(previous => previous.filter(m => m.target)); setStatus('syncing'); setError('');
        }
        if (r.history_batch) {
          if (r.request_id !== joinId.current) throw new Error('Unexpected history response.');
          const p = r.history_batch; const fresh = protocol.current.history(p).map(convert);
          setMessages(previous => [...previous, ...fresh].slice(-500));
          if (p.complete) {
            if (p.last_image?.length && !protocol.current.seen.has(p.last_image_message_id)) append({ id: p.last_image_message_id, sender: 'Room history', text: 'Last shared image', time: 0, image: dataUrl(p.last_image, p.last_image_mime) });
            setStatus('connected');
          }
        }
        if (r.broadcast && protocol.current.live(r.broadcast)) append(convert(r.broadcast), r.request_id);
        if (r.private_msg && protocol.current.remember(r.private_msg.message_id)) append(convert(r.private_msg), r.request_id);
        if (r.user_event) {
          const p = r.user_event;
          setUsers(previous => p.joined ? [...new Set([...previous, p.user_name])] : previous.filter(u => u !== p.user_name));
        }
        if (r.request_id) pending.current.delete(r.request_id);
      } catch (e) { setError(e instanceof Error ? e.message : 'Could not sync messages.'); ws.close(); }
    };
    ws.onclose = () => {
      if (socket.current !== ws) return;
      socket.current = null;
      setMessages(previous => previous.map(m => pending.current.has(m.id) ? { ...m, status: 'unknown' } : m)); pending.current.clear();
      setUsers([]);
      if (!desired.current) { setStatus('disconnected'); return; }
      setStatus('reconnecting'); setCountdown(10); let remaining = 10;
      clearTimer(); timer.current = setInterval(() => { remaining--; setCountdown(remaining); if (remaining <= 0) { clearTimer(); openRef.current(); } }, 1000);
    };
  }, []);
  openRef.current = open;
  const connect = (username: string, picture?: Attachment) => {
    stop(); config.current = { name: username, avatar: picture }; desired.current = true;
    setName(username); setAvatar(picture?.url); setMessages([]); setUsers([]); setError(''); open();
  };
  const demo = () => { stop(); setName('You'); setAvatar(undefined); setStatus('demo'); setUsers(['You', 'Maya', 'Theo', 'Lena']); setMessages(demoMessages); setError(''); };
  const send = (text: string, target?: string, file?: Attachment) => {
    if (Array.from(text).length > 25) { setError('Keep your message to 25 characters.'); return false; }
    if (!text.trim() && !file) return false;
    const id = crypto.randomUUID();
    if (status === 'demo') { append({ id, sender: 'You', target, text, time: Date.now(), image: file?.url }); return true; }
    if (status !== 'connected' || socket.current?.readyState !== WebSocket.OPEN) { setError('Reconnect before sending. Your draft is safe.'); return false; }
    if (target && !users.includes(target)) { setError('This person is currently offline.'); return false; }
    try {
      const payload = { text, image: file?.bytes, image_mime: file?.mime || '' };
      const command = { protocol_version: 2, request_id: id, ...(target ? { type: 2, private_chat: { ...payload, target_name: target } } : { type: 1, chat: payload }) };
      socket.current.send(Request.encode(Request.create(command)).finish()); pending.current.add(id);
      append({ id, sender: name, target, text, time: Date.now(), image: file?.url, avatar, status: 'sending' }); return true;
    } catch { setError('Could not send. Please try again.'); return false; }
  };
  return { messages, users, name, avatar, status, error, setError, countdown, connect, demo, send, disconnect: () => { stop(); setStatus('disconnected'); setUsers([]); } };
}
