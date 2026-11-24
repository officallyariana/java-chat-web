'use strict';
const config = JSON.parse(document.querySelector('#chat-config').textContent);
const $ = (id) => document.getElementById(id);
const encoder = new TextEncoder();
const decoder = new TextDecoder('utf-8', {fatal: true});
const roomSet = new Set(['lobby']);
let socket, connected = false, room = 'lobby', privateWith = null, counter = 0;
const pending = new Map();
function b64(text) { const bytes = encoder.encode(text); let binary=''; for(const b of bytes) binary += String.fromCharCode(b); return btoa(binary); }
function unb64(text) { const binary=atob(text); const bytes=Uint8Array.from(binary,c=>c.charCodeAt(0)); return decoder.decode(bytes); }
function encode(...fields) { return fields.map(b64).join('\t'); }
function decode(frame) { return frame.split('\t').map(unb64); }
function notice(text) { addLine(text, true); }
function addLine(text, system=false) { const row=document.createElement('div'); row.className='bubble'+(system?' system':''); row.textContent=text; $('messages').append(row); $('messages').scrollTop=$('messages').scrollHeight; }
function setStatus(state) { connected = state === 'Connected'; $('connection').textContent=state; $('connection-dot').classList.toggle('connected',connected); $('message').disabled=!connected; $('send').disabled=!connected; }
function send(...fields) { if(connected && socket.readyState === WebSocket.OPEN) { socket.send(encode(...fields)); return true; } return false; }
function refreshList(element, names, kind) { const parent=$(element); parent.replaceChildren(); for(const name of names) { if(!name) continue; const btn=document.createElement('button'); btn.type='button'; btn.textContent=(kind==='room'?'# ':'● ')+name; if(kind==='room' && privateWith===null && name.toLowerCase()===room.toLowerCase()) btn.classList.add('selected'); btn.onclick=()=> { if(kind==='room') {privateWith=null;send('JOIN',name);} else if(name.toLowerCase()!==config.name.toLowerCase()){privateWith=name;showChannel();send('DMHISTORY',name);} }; parent.append(btn); } }
function showChannel() { $('channel').textContent=privateWith?'@ '+privateWith:'# '+room; $('messages').replaceChildren(); refreshList('rooms',[...roomSet],'room'); }
function handle(fields) { const [type,a,b,c] = fields; switch(type) {
 case 'WELCOME': room=b||'lobby';setStatus('Connected as '+config.name);connected=true;$('connection-dot').classList.add('connected');$('message').disabled=false;$('send').disabled=false;showChannel();notice('Connected as '+a);send('ROOMS');send('USERS');send('HISTORY');break;
 case 'ROOM_MSG': if(!privateWith && a.toLowerCase()===room.toLowerCase()) addLine(b+': '+c);break;
 case 'DM_MSG': {const other=a.toLowerCase()===config.name.toLowerCase()?b:a;if(privateWith && other.toLowerCase()===privateWith.toLowerCase()) addLine(a+': '+c);else notice('Private message from '+other+' — select their name to view it.');break;}
 case 'JOINED': room=a;privateWith=null;roomSet.add(room);showChannel();send('ROOMS');send('HISTORY');break;
 case 'ROOMS': {const list=a?a.split(', ').filter(Boolean):[];list.forEach(n=>roomSet.add(n));refreshList('rooms',list,'room');break;}
 case 'USERS':refreshList('users',a?a.split(', ').filter(Boolean):[],'user');break;
 case 'HISTORY_START':if((a==='room'&&!privateWith&&b.toLowerCase()===room.toLowerCase())||(a==='private'&&privateWith&&b.toLowerCase()===privateWith.toLowerCase())){$('messages').replaceChildren();notice('— Previous messages —');}break;
 case 'HISTORY_MSG':addLine(a+' | '+b+': '+c);break;
 case 'HISTORY_END':notice('— End of history —');break;
 case 'ACK':{const item=pending.get(a);if(item){const ms=(performance.now()-item).toFixed(1);$('performance').textContent=`${b} ${c}: ${ms} ms RTT`;pending.delete(a);}break;}
 case 'INFO':notice(a);send('USERS');break;
 case 'ERROR':notice('Error: '+a);break;
 default:notice('Unknown server event '+type);
 }}
function connect() { socket=new WebSocket(config.ws);setStatus('Connecting…');socket.onopen=()=>{};socket.onmessage=(event)=>{try{handle(decode(event.data));}catch(e){notice('Invalid server message');}};socket.onerror=()=>notice('Could not connect to the Java WebSocket bridge.');socket.onclose=()=>{setStatus('Disconnected');notice('Connection closed. Reload to reconnect.');}; }
$('composer').addEventListener('submit',ev=>{ev.preventDefault();const text=$('message').value.trim();if(!text||text.length>2000||!connected)return;const id=String(++counter);const ok=privateWith?send('DM',id,privateWith,text):send('SAY',id,text);if(ok){pending.set(id,performance.now());$('message').value='';}});
$('new-room').onclick=()=>{const name=prompt('Room name (letters, numbers, _ or -):');if(name){if(!/^[A-Za-z0-9_-]{1,30}$/.test(name.trim()))notice('Invalid room name.');else send('JOIN',name.trim());}};
$('refresh').onclick=()=>{send('ROOMS');send('USERS');};
$('room-chat').onclick=()=>{privateWith=null;showChannel();send('HISTORY');};
$('history').onclick=()=>privateWith?send('DMHISTORY',privateWith):send('HISTORY');
connect();
