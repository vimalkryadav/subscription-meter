import { serveStdio } from '@modelcontextprotocol/server/stdio';
import { config, HttpAdminApi } from './api.js';
import { createServer } from './server.js';

try {
  const settings = config(process.env);
  await serveStdio(() => createServer(new HttpAdminApi(settings), settings));
} catch {
  process.stderr.write('control-usage MCP could not start. Check the documented environment, Node version and installation.\n');
  process.exitCode = 1;
}
