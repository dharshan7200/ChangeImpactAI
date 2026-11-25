import { exec, execFile } from 'child_process';
import crypto from 'crypto';
import https from 'https';

export async function handler(db: any, name: string, cmd: string, html: string, el: HTMLElement) {
  await db.query("SELECT * FROM users WHERE name = '" + name + "'"); // expect: cia.js.sql-injection.concatenated-query
  await db.query(`SELECT * FROM users WHERE name = '${name}'`); // expect: cia.js.sql-injection.concatenated-query
  await db.query('SELECT * FROM users WHERE name = $1', [name]); // ok
  exec(cmd); // expect: cia.js.injection.command-execution
  exec('ls -l'); // ok
  execFile('ls', [name]); // ok
  /^a+$/.exec(name); // ok
  eval(name); // expect: cia.js.injection.eval
  el.innerHTML = html; // expect: cia.js.xss.raw-html
  el.textContent = html; // ok
  crypto.createHash('md5').update(name); // expect: cia.js.crypto.weak-hash
  crypto.createHash('sha256').update(name); // ok
  new https.Agent({ rejectUnauthorized: false }); // expect: cia.js.tls.verification-disabled
  new https.Agent({ rejectUnauthorized: true }); // ok
}

const apiKey = "sk-live-1234567890"; // expect: cia.js.secrets.hardcoded-credential
const apiKeyFromEnv = process.env.API_KEY; // ok
