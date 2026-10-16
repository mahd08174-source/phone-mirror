const http = require('http'), fs = require('fs'), path = require('path');
const { WebSocketServer } = require('ws');

const page = fs.readFileSync(path.join(__dirname, 'public', 'index.html'));
const server = http.createServer((req, res) => {
  if (req.url === '/health') { res.writeHead(200); return res.end('ok'); }
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(page);
});

const wss = new WebSocketServer({ server, maxPayload: 8 * 1024 * 1024, perMessageDeflate: false });
const rooms = new Map(); // code -> { host, viewer }
const fails = new Map();  // ip -> { n, reset }
const j = o => JSON.stringify(o);
const ipOf = req => (req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').split(',')[0].trim();
const tooMany = ip => { const f = fails.get(ip); return f && f.reset > Date.now() && f.n >= 8; };
const noteFail = ip => {
  const f = fails.get(ip);
  if (!f || f.reset <= Date.now()) fails.set(ip, { n: 1, reset: Date.now() + 60000 }); else f.n++;
};

wss.on('connection', (ws, req) => {
  const q = new URL(req.url, 'http://x').searchParams;
  const role = q.get('role'), code = q.get('code') || '';
  if (!/^\d{6}$/.test(code) || (role !== 'host' && role !== 'viewer')) return ws.close(1008, 'bad request');
  const ip = ipOf(req);

  if (role === 'host') {
    if (rooms.has(code)) return ws.close(1008, 'code in use');
    rooms.set(code, { host: ws, viewer: null });
    ws.send(j({ type: 'ready' }));
  } else {
    if (tooMany(ip)) { ws.send(j({ type: 'error', message: 'Too many attempts. Wait a minute.' })); return ws.close(); }
    const room = rooms.get(code);
    if (!room || room.viewer) { noteFail(ip); ws.send(j({ type: 'error', message: 'No open session with that code.' })); return ws.close(); }
    room.viewer = ws; // one viewer per code, session ends when either side leaves
    ws.send(j({ type: 'joined' }));
    room.host.send(j({ type: 'viewer-joined' }));
  }

  ws.on('message', (data, isBinary) => {
    const room = rooms.get(code); if (!room) return;
    const to = role === 'host' ? room.viewer : room.host;
    if (!to || to.readyState !== 1) return;
    if (isBinary && to.bufferedAmount > 2e5) return; // drop frames instead of queueing lag
    to.send(data, { binary: isBinary });
  });

  ws.on('close', () => {
    const room = rooms.get(code);
    if (!room || (room.host !== ws && room.viewer !== ws)) return;
    rooms.delete(code);
    const other = role === 'host' ? room.viewer : room.host;
    if (other) { try { other.send(j({ type: role === 'host' ? 'host-left' : 'viewer-left' })); other.close(); } catch (e) {} }
  });
});

server.listen(process.env.PORT || 8080, () => console.log('relay up'));
