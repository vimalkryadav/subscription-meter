# control-usage admin MCP

See the [setup and complete tool reference](../docs/mcp-guide.md) and
[administrator guide](../docs/admin-guide.md). Both guides are also available in the dashboard.

```sh
npm ci --prefix mcp --ignore-scripts
npm run build --prefix mcp
python3 mcp/login.py https://YOUR_CURRENT_PLATFORM_HOST
```

Adapt [client-config.example.json](client-config.example.json) to your trusted client's stdio
configuration. Use absolute paths. The private session file is an expiring administrator
credential bound to one HTTPS origin; never paste its contents into configuration or chat.

The default process exposes 15 read/documentation tools. `CONTROL_USAGE_MCP_WRITES=true`
adds seven named administrative actions. `CONTROL_USAGE_MCP_CONTENT=true` separately adds
two sensitive capture tools. No provider inference, key issuance or generic command tool is exposed.

Run `npm test --prefix mcp` for isolated protocol, schema and transport tests. The bridge is
local to the client and is updated separately from the website. There is no public `/mcp` URL.
