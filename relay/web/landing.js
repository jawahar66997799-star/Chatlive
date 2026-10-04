const $=s=>document.querySelector(s);
const createTab=$('#createTab'),joinTab=$('#joinTab');
const createPanel=$('#createPanel'),joinPanel=$('#joinPanel');
const createCode=$('#createCode'),roomCode=$('#roomCode');
const createHint=$('#createHint'),joinHint=$('#joinHint');
const result=$('#roomResult'),resultCode=$('#resultCode'),resultLink=$('#resultLink');
const openHostApp=$('#openHostApp');

function normalize(v){return String(v||'').replace(/\D/g,'').slice(0,6)}
function valid(v){return /^\d{6}$/.test(v)}
function randomCode(){
  if(self.crypto?.getRandomValues){
    const x=new Uint32Array(1);crypto.getRandomValues(x);return String(x[0]%1000000).padStart(6,'0');
  }
  return String(Math.floor(Math.random()*1000000)).padStart(6,'0');
}
function setTab(mode){
  const creating=mode==='create';
  createTab.classList.toggle('active',creating);joinTab.classList.toggle('active',!creating);
  createTab.setAttribute('aria-selected',String(creating));joinTab.setAttribute('aria-selected',String(!creating));
  createPanel.hidden=!creating;joinPanel.hidden=creating;
}
function roomUrl(code){return new URL('/room/'+encodeURIComponent(code),location.origin).href}
function hostIntent(code){
  const q=encodeURIComponent(code);
  if(/Android/i.test(navigator.userAgent)){
    return 'intent://host?room='+q+'#Intent;scheme=jls;package=com.jawahar.livesync;end';
  }
  return 'jls://host?room='+q;
}
function flash(el,msg,bad=false){el.className='hint'+(bad?' bad':'');el.textContent=msg}
async function copy(text,el,label){
  try{await navigator.clipboard.writeText(text);flash(el,label)}
  catch{flash(el,'Copy failed. Select and copy manually.',true)}
}

createTab.addEventListener('click',()=>setTab('create'));
joinTab.addEventListener('click',()=>setTab('join'));
for(const el of [createCode,roomCode]) el.addEventListener('input',()=>{const n=normalize(el.value);if(el.value!==n)el.value=n});
$('#generateCode').addEventListener('click',()=>{createCode.value=randomCode();createCode.select();flash(createHint,'Fresh room code generated.')});
$('#createRoom').addEventListener('click',()=>{
  const code=normalize(createCode.value);
  if(!valid(code)){flash(createHint,'Enter a complete 6-digit room code.',true);createCode.focus();return}
  const link=roomUrl(code);
  resultCode.textContent=code;resultLink.textContent=link;openHostApp.href=hostIntent(code);result.hidden=false;
  flash(createHint,'Room ready. Open the host app to activate it.');
});
$('#copyLink').addEventListener('click',()=>copy(resultLink.textContent,createHint,'Friend link copied.'));
$('#copyCode').addEventListener('click',()=>copy(resultCode.textContent,createHint,'Room code copied.'));
$('#shareLink').addEventListener('click',async()=>{
  const code=resultCode.textContent,link=resultLink.textContent;
  if(navigator.share){
    try{await navigator.share({title:'Jawahar Live Sync',text:'Join my Jawahar Live Sync room '+code,url:link});return}catch(e){if(e?.name==='AbortError')return}
  }
  await copy(link,createHint,'Friend link copied.');
});
$('#joinForm').addEventListener('submit',e=>{
  e.preventDefault();const code=normalize(roomCode.value);
  if(!valid(code)){flash(joinHint,'Enter the complete 6-digit room code.',true);roomCode.focus();return}
  location.assign('/room/'+encodeURIComponent(code));
});

const prefill=normalize(new URL(location.href).searchParams.get('room'));
if(valid(prefill)){roomCode.value=prefill;setTab('join')}
else createCode.value=randomCode();
