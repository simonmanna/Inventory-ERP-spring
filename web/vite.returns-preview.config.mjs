// Temporary preview config: proxies to a locally built API on :3011 (docker ierp-api holds :3001).
import base from './vite.config.ts';
export default { ...base, server: { ...base.server, port: 5179, proxy: { '/api/v1': { target: 'http://localhost:3011', changeOrigin: true } } } };
