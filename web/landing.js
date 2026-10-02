const form=document.querySelector('#joinForm');
const input=document.querySelector('#roomCode');
const hint=document.querySelector('#hint');
function normalize(v){return String(v||'').replace(/\D/g,'').slice(0,6)}
input.addEventListener('input',()=>{const n=normalize(input.value);if(input.value!==n)input.value=n;hint.className='hint';hint.textContent='Listening opens directly in this browser.'});
form.addEventListener('submit',e=>{
  e.preventDefault();
  const code=normalize(input.value);
  if(!/^\d{6}$/.test(code)){hint.className='hint bad';hint.textContent='Enter the complete 6-digit room code.';input.focus();return;}
  location.assign('/room/'+encodeURIComponent(code));
});