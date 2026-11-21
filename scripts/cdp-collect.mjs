import fs from 'fs';
const [,, wsUrl, jsFile, outFile] = process.argv;
const expr = fs.readFileSync(jsFile, 'utf8');
const ws = new WebSocket(wsUrl); let id = 0; const pending = new Map();
function send(m, p = {}) { return new Promise((res, rej) => { const i = ++id; pending.set(i, { res, rej }); ws.send(JSON.stringify({ id: i, method: m, params: p })); setTimeout(() => { if (pending.has(i)) { pending.delete(i); rej(new Error('timeout ' + m)); } }, 120000); }); }
ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); } };
await new Promise(r => ws.onopen = r);
await send('Runtime.enable');
const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true, userGesture: true });
if (r.exceptionDetails) { console.error('EXC', JSON.stringify(r.exceptionDetails).slice(0, 800)); process.exit(1); }
const v = r.result.value;
fs.writeFileSync(outFile, typeof v === 'string' ? v : JSON.stringify(v, null, 1));
console.log('OK bytes=' + fs.statSync(outFile).size + ' -> ' + outFile);
ws.close();
