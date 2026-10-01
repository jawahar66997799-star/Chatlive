import puppeteer from "puppeteer-core";

const base=(process.env.PUBLIC_BASE||"").replace(/\/$/,"");
const token=process.env.GUEST_TOKEN||"";
const exe=process.env.CHROMIUM_PATH||"/usr/bin/chromium-browser";
if(!base||!token) throw new Error("missing PUBLIC_BASE/GUEST_TOKEN");

const url=base+"/r/"+encodeURIComponent(token)+"?probe="+Date.now();
const browser=await puppeteer.launch({
  executablePath:exe,
  headless:true,
  args:[
    "--no-sandbox",
    "--disable-setuid-sandbox",
    "--autoplay-policy=no-user-gesture-required",
    "--use-fake-ui-for-media-stream",
    "--disable-dev-shm-usage"
  ]
});

const page=await browser.newPage();
const pageErrors=[];
const consoleErrors=[];
page.on("pageerror",e=>pageErrors.push(String(e)));
page.on("console",m=>{if(m.type()==="error")consoleErrors.push(m.text())});

try{
  await page.goto(url,{waitUntil:"domcontentloaded",timeout:20000});
  await page.waitForSelector("#join",{visible:true,timeout:10000});
  const disabled=await page.$eval("#join",el=>el.disabled);
  if(!disabled) await page.click("#join");

  await page.waitForFunction(
    ()=>window.__JLS_METRICS__ && window.__JLS_METRICS__.decodedRmsDb>-70,
    {timeout:15000}
  );

  await page.waitForFunction(
    ()=>{
      const m=window.__JLS_METRICS__;
      return m && m.audioState==="running" && m.scheduledFrames>3 &&
        (m.workletAlive||m.directPlayback) &&
        (m.outputRmsDb>-90||m.directPlayback);
    },
    {timeout:15000}
  );

  try{
    await page.waitForFunction(
      ()=>{
        const m=window.__JLS_METRICS__;
        return m && !m.fallbackPlayback &&
          m.playoutGate==="precision scheduled" &&
          m.outputRmsDb>-90;
      },
      {timeout:8000}
    );
  }catch{}

  const metrics=await page.evaluate(()=>({...window.__JLS_METRICS__}));
  const diag=await page.$eval("#diagText",el=>el.innerText);
  const audible=metrics.outputRmsDb>-90||metrics.directPlayback;
  const scheduled=metrics.scheduledFrames>3;
  const engine=metrics.workletAlive||metrics.directPlayback;
  const ok=metrics.audioState==="running" &&
    metrics.decodedRmsDb>-70 && audible && scheduled && engine &&
    metrics.schedulerErrors===0 && metrics.workletProcessorErrors===0 &&
    pageErrors.length===0;

  console.log("PUBLIC_BROWSER_PLAYOUT "+(ok?"PASS":"FAIL")+" "+JSON.stringify({
    decodedRmsDb:metrics.decodedRmsDb,
    outputRmsDb:metrics.outputRmsDb,
    audioState:metrics.audioState,
    scheduledFrames:metrics.scheduledFrames,
    workletAlive:metrics.workletAlive,
    workletQuanta:metrics.workletQuanta,
    directPlayback:metrics.directPlayback,
    directSources:metrics.directSources,
    directScheduledFrames:metrics.directScheduledFrames,
    fallbackPlayback:metrics.fallbackPlayback,
    playoutGate:metrics.playoutGate,
    schedulerErrors:metrics.schedulerErrors,
    lastSchedulerError:metrics.lastSchedulerError,
    workletProcessorErrors:metrics.workletProcessorErrors,
    audioEngineRebuilds:metrics.audioEngineRebuilds,
    faultCode:metrics.faultCode,
    pageErrors,
    consoleErrors,
    diag
  }));

  if(!ok) process.exitCode=2;
} finally {
  await browser.close();
}
