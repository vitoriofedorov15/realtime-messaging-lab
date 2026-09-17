import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import ArrowDown from 'lucide-react/icons/arrow-down';
import ArrowRight from 'lucide-react/icons/arrow-right';
import ArrowUp from 'lucide-react/icons/arrow-up';
import Bookmark from 'lucide-react/icons/bookmark';
import ChevronRight from 'lucide-react/icons/chevron-right';
import Hash from 'lucide-react/icons/hash';
import ImagePlus from 'lucide-react/icons/image-plus';
import Info from 'lucide-react/icons/info';
import Leaf from 'lucide-react/icons/leaf';
import LogOut from 'lucide-react/icons/log-out';
import Menu from 'lucide-react/icons/menu';
import MessageCircle from 'lucide-react/icons/message-circle';
import MoreHorizontal from 'lucide-react/icons/more-horizontal';
import Search from 'lucide-react/icons/search';
import Smile from 'lucide-react/icons/smile';
import Sparkles from 'lucide-react/icons/sparkles';
import Wifi from 'lucide-react/icons/wifi';
import X from 'lucide-react/icons/x';
import { useChat, color, initials, readImage, validName, type Attachment } from './chat';

function Avatar({ name, image, small = false }: { name: string; image?: string; small?: boolean }) {
  return <span className={`avatar ${color(name)} ${small ? 'small' : ''}`}>{image ? <img src={image} alt={`${name}'s avatar`} /> : initials(name)}</span>;
}
export default function App() {
  const chat = useChat();
  const [view, setView] = useState('general');
  const [search, setSearch] = useState('');
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [attachment, setAttachment] = useState<Attachment>();
  const [loadingFile, setLoadingFile] = useState(false);
  const fileVersion = useRef(0);
  const avatarVersion = useRef(0);
  const [saved, setSaved] = useState<Set<string>>(new Set());
  const [showJoin, setShowJoin] = useState(false);
  const [joinName, setJoinName] = useState('');
  const [joinAvatar, setJoinAvatar] = useState<Attachment>();
  const [joinError, setJoinError] = useState('');
  const [avatarLoading, setAvatarLoading] = useState(false);
  const [sidebar, setSidebar] = useState(false);
  const [details, setDetails] = useState(false);
  const [lightbox, setLightbox] = useState<string>();
  const [emoji, setEmoji] = useState(false);
  const [showLatest, setShowLatest] = useState(false);
  const [newMessages, setNewMessages] = useState(false);
  const previousCount = useRef(chat.messages.length);
  const list = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const dialog = useRef<HTMLDialogElement>(null);
  const demo = chat.status === 'demo';
  const direct = view !== 'general' && view !== 'saved';
  const title = view === 'general' ? 'The common room' : view === 'saved' ? 'Saved for later' : view;
  const draft = drafts[view] || '';
  const count = Array.from(draft).length;
  const knownUsers = useMemo(() => [...new Set([...chat.users, ...chat.messages.flatMap(m => m.target ? [m.sender, m.target] : [])])].filter(n => n !== chat.name), [chat.users, chat.messages, chat.name]);
  const filtered = chat.messages.filter(m => {
    const belongs = view === 'saved' ? saved.has(m.id) : direct ? !!m.target && ((m.sender === chat.name && m.target === view) || (m.sender === view && m.target === chat.name)) : !m.target;
    return belongs && (!search || `${m.text} ${m.sender}`.toLowerCase().includes(search.toLowerCase()));
  });
  const media = filtered.filter(m => m.image);
  const ready = demo || chat.status === 'connected';
  const sendable = ready && !loadingFile && count <= 25 && (!!draft.trim() || !!attachment);
  const chooseView = (next: string) => { setView(next); setSearch(''); setSidebar(false); setEmoji(false); setAttachment(undefined); fileVersion.current++; setLoadingFile(false); setNewMessages(false); };
  const latest = () => { list.current?.scrollTo({ top: list.current.scrollHeight, behavior: 'smooth' }); setNewMessages(false); };
  useEffect(() => { if (previousCount.current !== chat.messages.length) { if (!showLatest) latest(); else setNewMessages(true); previousCount.current = chat.messages.length; } }, [chat.messages]);
  useEffect(() => { list.current?.scrollTo({ top: demo ? 0 : list.current.scrollHeight }); setShowLatest(false); }, [view]);
  useEffect(() => { if (showJoin) dialog.current?.showModal(); else dialog.current?.close(); }, [showJoin]);
  useEffect(() => { const escape = (e: KeyboardEvent) => { if (e.key === 'Escape') { setLightbox(undefined); setEmoji(false); setSidebar(false); setDetails(false); } }; window.addEventListener('keydown', escape); return () => window.removeEventListener('keydown', escape); }, []);
  function send(e?: FormEvent) {
    e?.preventDefault(); if (!sendable) return;
    if (chat.send(draft, direct ? view : undefined, attachment)) { setDrafts(previous => ({ ...previous, [view]: '' })); setAttachment(undefined); fileVersion.current++; setEmoji(false); latest(); input.current?.focus(); }
  }
  async function attach(file?: File) {
    if (!file) return; const version = ++fileVersion.current; setAttachment(undefined); setLoadingFile(true);
    try { const result = await readImage(file); if (version === fileVersion.current) setAttachment(result); }
    catch (e) { if (version === fileVersion.current) chat.setError((e as Error).message); }
    finally { if (version === fileVersion.current) setLoadingFile(false); }
  }
  function toggleSave(id: string) { setSaved(previous => { const next = new Set(previous); next.has(id) ? next.delete(id) : next.add(id); return next; }); }
  function join(e: FormEvent) {
    e.preventDefault(); const name = joinName.trim();
    if (!validName(name)) { setJoinError('Use 1–50 letters, numbers, underscores or hyphens.'); return; }
    chat.connect(name, joinAvatar); chooseView('general'); setSaved(new Set()); setShowJoin(false);
  }
  const statusLabel = demo ? 'Demo workspace' : chat.status === 'connected' ? 'Connected' : chat.status === 'reconnecting' ? `Retrying in ${chat.countdown}s` : chat.status === 'syncing' ? 'Loading history' : chat.status === 'connecting' ? 'Connecting…' : 'Disconnected';
  return <div className="app-shell">
    <aside className={`sidebar ${sidebar ? 'is-open' : ''}`} aria-label="Workspace navigation">
      <a className="brand" href="#" onClick={e => { e.preventDefault(); chooseView('general'); }}><span className="brand-mark"><MessageCircle size={23} strokeWidth={2.3} /></span>common<span className="brand-dot">.</span></a>
      <div className="workspace-label"><span className="workspace-icon"><Leaf size={18} /></span><div>Our little workspace<small>A place to connect</small></div><span className="workspace-indicator" /></div>
      <div className="nav-label">YOUR SPACE</div>
      <button className={`nav-item ${view === 'general' ? 'active' : ''}`} onClick={() => chooseView('general')}><Hash size={19} />General<span className="nav-trailing">01</span></button>
      <button className={`nav-item ${view === 'saved' ? 'active' : ''}`} onClick={() => chooseView('saved')}><Bookmark size={18} />Saved messages{saved.size > 0 && <span className="nav-trailing">{saved.size}</span>}</button>
      <div className="nav-label dm-label">DIRECT MESSAGES<span>{knownUsers.length.toString().padStart(2, '0')}</span></div>
      <div className="dm-list">{knownUsers.map(user => <button className={`person-link ${view === user ? 'selected' : ''}`} key={user} onClick={() => chooseView(user)}><span className="avatar-wrap"><Avatar name={user} small /><i className={chat.users.includes(user) ? 'online' : 'offline'} /></span><span>{user}</span><ChevronRight size={14} /></button>)}{knownUsers.length === 0 && <p className="sidebar-empty">People appear here when they join.</p>}</div>
      <div className="sidebar-bottom"><div className="little-note"><Sparkles size={18} /><p>Good conversations<br />start with a little hello.</p><span className="note-squiggle">✳</span></div><div className="profile"><Avatar name={chat.name} image={chat.avatar} small /><div><strong>{chat.name}</strong><small>{demo ? 'Exploring Common' : statusLabel}</small></div><button className="icon-button" aria-label={demo ? 'Connect to server' : 'Leave room'} title={demo ? 'Connect to server' : 'Leave room'} onClick={() => demo ? setShowJoin(true) : chat.disconnect()}>{demo ? <ArrowRight size={18} /> : <LogOut size={18} />}</button></div></div>
    </aside>
    {sidebar && <button className="drawer-backdrop" aria-label="Close navigation" onClick={() => setSidebar(false)} />}
    <main className="workspace">
      <header className="topbar"><div className="breadcrumb"><button className="icon-button mobile-menu" aria-label="Open navigation" onClick={() => setSidebar(true)}><Menu size={20} /></button><span>Workspace</span><ChevronRight size={14} /><strong>{view === 'general' ? 'General' : view === 'saved' ? 'Saved' : 'Direct message'}</strong></div><div className="top-actions"><span className={`connection ${ready ? '' : 'offline'}`}><i />{statusLabel}</span><button className="connect-button" onClick={() => setShowJoin(true)}>{demo ? 'Join a live room' : 'Switch profile'}<ArrowRight size={15} /></button></div></header>
      <div className="conversation-layout">
        <section className="conversation" aria-label={title}>
          <div className="room-header"><span className="room-icon">{view === 'saved' ? <Bookmark size={25} /> : direct ? <MessageCircle size={26} /> : <Hash size={28} />}</span><div><h1>{title}<span className="title-dot">.</span></h1><p>{direct ? 'Just the two of you.' : view === 'saved' ? 'A few things worth keeping.' : 'A little space for everyone. Make yourself at home.'}</p></div><button className={`icon-button details-toggle ${details ? 'pressed' : ''}`} aria-label="Toggle room details" aria-expanded={details} onClick={() => setDetails(!details)}><Info size={20} /></button></div>
          <div className="conversation-toolbar"><div className="room-tabs"><span className="current-tab"><MessageCircle size={15} />Conversation</span><span className="message-total">{filtered.length}</span></div><label className="search"><Search size={16} /><input aria-label="Search messages" placeholder="Find a message" value={search} onChange={e => setSearch(e.target.value)} />{search && <button aria-label="Clear search" onClick={() => setSearch('')}><X size={14} /></button>}</label></div>
          {demo && <div className="demo-banner"><span className="demo-badge">PREVIEW</span><span>A sample conversation. Try it out — nothing is sent.</span><button onClick={() => setShowJoin(true)} aria-label="Connect preview to live server"><ArrowUp size={16} /></button></div>}
          {chat.error && <div className="error-banner" role="alert"><span>{chat.error}</span><button aria-label="Dismiss error" onClick={() => chat.setError('')}><X size={16} /></button></div>}
          <div className="messages" ref={list} onScroll={() => { const el = list.current!; setShowLatest(el.scrollHeight - el.scrollTop - el.clientHeight > 100); }}>
            {!search && view === 'general' && <div className="welcome-note"><span className="welcome-symbol">✳</span><div><h2>A new day, a good conversation.</h2><p>Share an idea, a little inspiration, or just a hello.</p></div></div>}
            {filtered.length > 0 && <div className="date-divider"><span>{demo ? 'TODAY' : 'CONVERSATION'}</span></div>}
            {filtered.map((message, index) => {
              const own = message.sender === chat.name; const grouped = index > 0 && filtered[index - 1].sender === message.sender && message.time - filtered[index - 1].time < 120000;
              return <article className={`message ${own ? 'own' : ''} ${grouped ? 'grouped' : ''}`} key={message.id}>
                <div className="message-avatar">{!grouped && <Avatar name={message.sender} image={message.avatar || (own ? chat.avatar : undefined)} small />}</div>
                <div className="message-content">{!grouped && <div className="message-meta"><strong>{own ? 'You' : message.sender}</strong>{own && <span className="you-tag">YOU</span>}<time>{message.time ? new Date(message.time).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' }) : 'Earlier'}</time></div>}
                <div className="bubble-wrap"><div className={`bubble ${message.image ? 'has-image' : ''}`}>{message.text && <p>{message.text}</p>}{message.image && <button className="message-image" onClick={() => setLightbox(message.image)} aria-label={`Open image from ${message.sender}`}><img src={message.image} alt={`Shared by ${message.sender}`} /></button>}</div><button className={`save-button ${saved.has(message.id) ? 'is-saved' : ''}`} title={saved.has(message.id) ? 'Remove from saved' : 'Save message'} aria-label={saved.has(message.id) ? 'Unsave message' : 'Save message'} onClick={() => toggleSave(message.id)}><Bookmark size={16} fill={saved.has(message.id) ? 'currentColor' : 'none'} /></button></div>
                {message.status && <span className={`message-status ${message.status}`}>{message.status === 'sending' ? 'Sending…' : message.status === 'failed' ? 'Not sent' : 'Delivery not confirmed'}</span>}
                </div>
              </article>;
            })}
            {filtered.length === 0 && <div className="empty-state"><span>{view === 'saved' ? <Bookmark size={28} /> : <MessageCircle size={30} />}</span><h2>{search ? 'Nothing here just yet.' : view === 'saved' ? 'Keep the good bits.' : direct ? `Say hello to ${view}.` : 'The room is yours.'}</h2><p>{search ? 'Try another word or a person’s name.' : view === 'saved' ? 'Bookmark any message to find it here later.' : 'Start a conversation with a little hello.'}</p></div>}
          </div>
          {showLatest && <button className="jump-latest" onClick={latest}>{newMessages ? 'New messages' : 'Back to latest'}<ArrowDown size={14} /></button>}
          {view !== 'saved' && <div className="composer-container"><form className={`composer ${count > 25 ? 'over-limit' : ''}`} onSubmit={send}>
            {attachment && <div className="attachment-preview"><img src={attachment.url} alt="Attachment preview" /><div><strong>{attachment.name}</strong><small>Ready to share</small></div><button type="button" aria-label="Remove attachment" onClick={() => { setAttachment(undefined); fileVersion.current++; }}><X size={16} /></button></div>}
            <textarea ref={input} aria-label="Message" rows={1} placeholder={ready ? direct ? `A little note to ${view}…` : 'What’s on your mind?' : 'Your draft stays here while we reconnect…'} value={draft} onChange={e => setDrafts(previous => ({ ...previous, [view]: e.target.value }))} onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); send(); } }} />
            <div className="composer-tools"><div className="tool-buttons"><button type="button" className="icon-button" aria-label="Attach image" title="PNG or JPEG, up to 1 MiB" onClick={() => fileInput.current?.click()} disabled={loadingFile}><ImagePlus size={19} /></button><button type="button" className={`icon-button ${emoji ? 'pressed' : ''}`} aria-label="Choose emoji" onClick={() => setEmoji(!emoji)}><Smile size={19} /></button><span className="composer-hint">{loadingFile ? 'Reading image…' : direct ? 'Direct message' : 'Message everyone'}</span></div><div className="send-tools"><span className={`char-counter ${count > 25 ? 'invalid' : ''}`}>{count}<span> / 25</span></span><button className="send-button" aria-label="Send message" disabled={!sendable}><ArrowUp size={20} /></button></div></div>
            {emoji && <div className="emoji-picker" aria-label="Emoji choices">{['👋', '🌿', '✨', '💚', '☕', '😊', '🎉', '👍'].map(item => <button type="button" key={item} onClick={() => { setDrafts(previous => ({ ...previous, [view]: draft + item })); setEmoji(false); input.current?.focus(); }}>{item}</button>)}</div>}
            <input ref={fileInput} type="file" accept="image/png,image/jpeg" hidden onChange={e => { void attach(e.target.files?.[0]); e.target.value = ''; }} />
          </form><div className="composer-footer"><span><span className="tiny-dot" />{demo ? 'Demo messages stay in this browser' : ready ? 'You’re connected. Say something good.' : statusLabel}</span><span>Enter to send <span className="keycap">↵</span></span></div></div>}
        </section>
        <aside className={`details ${details ? 'is-open' : ''}`} aria-label="Room details"><div className="details-heading"><span>ROOM DETAILS</span><button className="icon-button close-details" aria-label="Close room details" onClick={() => setDetails(false)}><X size={17} /></button><MoreHorizontal size={18} className="details-decoration" /></div><div className="room-art"><div className="art-circle one" /><div className="art-circle two" /><span className="art-flower">✳</span><span className="art-caption">a little room<br />for good things.</span></div><h2>{direct ? 'A quieter corner.' : 'Better, together.'}</h2><p className="details-description">{direct ? 'For the conversations that only need two people.' : 'A shared space for small ideas, fresh perspectives, and everyday conversation.'}</p><div className="members-heading"><span>{demo ? 'SAMPLE MEMBERS' : 'IN THE ROOM'}</span><span>{chat.users.length.toString().padStart(2, '0')}</span></div><div className="member-list">{chat.users.map(user => <button key={user} onClick={() => { if (user !== chat.name) chooseView(user); }} className="member" disabled={user === chat.name}><Avatar name={user} image={user === chat.name ? chat.avatar : undefined} small /><div><strong>{user === chat.name ? `${user} (you)` : user}</strong><small>{demo ? 'Demo profile' : 'Online now'}</small></div><span className="member-dot" /></button>)}{chat.users.length === 0 && <p className="sidebar-empty">Connect to see who’s here.</p>}</div><div className="shared-heading"><span>SHARED MOMENTS</span><span>{media.length.toString().padStart(2, '0')}</span></div><div className="shared-media">{media.slice(-4).map(m => <button key={m.id} onClick={() => setLightbox(m.image)} aria-label={`View shared image from ${m.sender}`}><img src={m.image} alt={`Shared by ${m.sender}`} /></button>)}{media.length === 0 && <p>Images shared here will have a home here, too.</p>}</div><div className="room-footer"><Leaf size={15} /><span>A little less noise.<br />A little more connection.</span></div></aside>
      </div>
    </main>
    <dialog ref={dialog} className="join-dialog" onCancel={() => setShowJoin(false)} onClick={e => { if (e.target === dialog.current) setShowJoin(false); }}><button className="dialog-close icon-button" aria-label="Close connection dialog" onClick={() => setShowJoin(false)}><X size={20} /></button><div className="dialog-symbol"><MessageCircle size={30} /></div><span className="eyebrow">MAKE YOURSELF AT HOME</span><h2>A little hello<br />goes a long way<span>.</span></h2><p>Pick a name and step into the conversation.<br />No account needed.</p><form onSubmit={join}><label className="name-label" htmlFor="join-name">What should we call you?</label><input id="join-name" autoComplete="nickname" placeholder="Your name" value={joinName} onChange={e => { setJoinName(e.target.value); setJoinError(''); }} autoFocus /><label className="avatar-picker"><Avatar name={joinName || 'Hi'} image={joinAvatar?.url} small /><span>{avatarLoading ? 'Reading avatar…' : joinAvatar ? joinAvatar.name : 'Add a little personality'}<small>Optional avatar · PNG / JPEG · 200 KiB</small></span><ImagePlus size={18} /><input type="file" accept="image/png,image/jpeg" hidden onChange={async e => { const file = e.target.files?.[0]; e.target.value = ''; if (!file) return; const version = ++avatarVersion.current; setJoinAvatar(undefined); setAvatarLoading(true); try { const result = await readImage(file, true); if (version === avatarVersion.current) setJoinAvatar(result); } catch (err) { if (version === avatarVersion.current) setJoinError((err as Error).message); } finally { if (version === avatarVersion.current) setAvatarLoading(false); } }} /></label>{joinError && <p className="join-error" role="alert">{joinError}</p>}<button className="join-submit" disabled={avatarLoading}>Join the conversation<ArrowRight size={18} /></button></form><button className="demo-link" onClick={() => { chat.demo(); chooseView('general'); setSaved(new Set()); setShowJoin(false); }}>Just exploring? Try the demo</button><div className="dialog-note"><Wifi size={13} />Connects to your running chat server</div></dialog>
    {lightbox && <div className="lightbox" role="dialog" aria-modal="true" aria-label="Image preview" onClick={() => setLightbox(undefined)}><button className="lightbox-close" aria-label="Close image preview" autoFocus><X size={25} /></button><img src={lightbox} alt="Expanded shared image" /></div>}
  </div>;
}
