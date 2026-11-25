const localtunnel = require('localtunnel');

const PORT = 5173;
const PREFERRED_SUBDOMAIN = 'changeimpactai';
const BACKUP_SUBDOMAIN = 'changeimpact-ai';

let activeTunnel = null;
let isConnecting = false;

async function connect() {
  if (isConnecting) return;
  isConnecting = true;

  if (activeTunnel) {
    try { activeTunnel.close(); } catch (e) {}
    activeTunnel = null;
  }

  console.log(`Connecting tunnel to port ${PORT}...`);

  let tunnel = null;

  // Try preferred subdomain first
  try {
    tunnel = await localtunnel({
      port: PORT,
      subdomain: PREFERRED_SUBDOMAIN,
      local_host: '127.0.0.1'
    });
  } catch (err) {
    console.warn(`Error requesting ${PREFERRED_SUBDOMAIN}: ${err.message}`);
  }

  // If loca.lt gave a random animal name because changeimpactai was temporarily locked, try backup
  if (!tunnel || !tunnel.url.includes(PREFERRED_SUBDOMAIN)) {
    console.warn(`Subdomain '${PREFERRED_SUBDOMAIN}' not available (assigned: ${tunnel ? tunnel.url : 'none'}). Trying '${BACKUP_SUBDOMAIN}'...`);
    if (tunnel) {
      try { tunnel.close(); } catch (e) {}
    }
    try {
      tunnel = await localtunnel({
        port: PORT,
        subdomain: BACKUP_SUBDOMAIN,
        local_host: '127.0.0.1'
      });
    } catch (err) {
      console.warn(`Error requesting ${BACKUP_SUBDOMAIN}: ${err.message}`);
    }
  }

  if (tunnel) {
    activeTunnel = tunnel;
    console.log(`===============================================`);
    console.log(`>>> LIVE TUNNEL URL: ${tunnel.url}`);
    console.log(`===============================================`);

    tunnel.on('close', () => {
      console.log('Tunnel closed by remote server. Reconnecting in 5s...');
      isConnecting = false;
      setTimeout(connect, 5000);
    });

    tunnel.on('error', (err) => {
      console.log(`Tunnel socket error: ${err.message}. Reconnecting in 5s...`);
      isConnecting = false;
      setTimeout(connect, 5000);
    });
  } else {
    console.error('Failed to establish any tunnel. Retrying in 10s...');
    setTimeout(connect, 10000);
  }

  isConnecting = false;
}

connect();
setInterval(() => {}, 1000000);
