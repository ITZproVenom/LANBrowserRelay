# LANLink Supabase backend

This directory contains the source of the LANLink relay Edge Function and the ordered database migrations.

## Runtime layout

- `functions/lanlink-mcp/index.ts` is the HTTP/MCP control plane.
- `public.lanlink_api_keys` stores SHA-256 hashes for MCP client keys.
- `public.lanlink_agents` stores TV-agent token hashes, heartbeat state, version, and advertised capabilities.
- `public.lanlink_commands` is the short-lived command queue.
- `public.lanlink_devices` stores the latest device inventory reported by an agent.
- `public.lanlink_agent_enrollments` stores one-time activation-code hashes.
- `public.lanlink_claim_commands(uuid, integer)` atomically claims queued commands using row locks with `SKIP LOCKED`.

The Android TV is the LAN execution agent. The iPhone controller pairs with that TV over the private LAN. Supabase is the authenticated relay for ChatGPT/MCP requests; it is not a general-purpose remote shell.

## Migration history

Apply the files in `migrations/` in ascending timestamp order. The migration set corresponds to the seven migrations recorded by the production project as of 2026-11-04.

The initial schema migration intentionally does **not** insert the production MCP API-key hash or any TV-agent token hash. Those values are credentials, not schema. A clean database therefore needs fresh credentials provisioned through a trusted administrative process before the relay can accept MCP calls or enroll a TV.

## Credential provisioning

Never put raw API keys, raw TV-agent tokens, one-time activation codes, or their hashes in source control, build logs, issue descriptions, or app binaries.

For a fresh project:

1. Generate a new high-entropy MCP key and store only its SHA-256 hash in `lanlink_api_keys.key_hash`; retain the raw key in the MCP client's secret store.
2. Create the first `lanlink_agents` row with a unique placeholder token hash and the intended agent name. The placeholder is replaced during enrollment.
3. Generate a separate 256-bit, single-use activation code. Store only its SHA-256 hash in `lanlink_agent_enrollments.code_hash`, associated with that agent and a short expiry. Deliver the raw code to the iPhone controller through a private channel.
4. The iPhone posts that code to `/agent/enroll`. The function consumes it once, generates the long-lived TV-agent token, and returns it with `Cache-Control: no-store`. The iPhone passes the token to the paired TV over the authenticated local controller API. The TV stores it encrypted with Android Keystore; Supabase stores only its hash.
5. Confirm that the TV heartbeat reports its actual version and only the capabilities its installed build implements.

Do not reuse historical activation codes. Issue a new one for each enrollment attempt or replacement TV installation.

## Access-control model

All `lanlink_*` tables have RLS enabled. The application has no direct `anon` or `authenticated` table grants and no public RLS policies. The Edge Function uses the server-side `service_role` key and performs its own API-key or agent-token authentication. Do not add browser/mobile clients using `service_role`, and do not add public policies to these tables.

The `lanlink_claim_commands` function runs as invoker with an empty search path. Execution is revoked from `PUBLIC`, `anon`, and `authenticated`, and granted only to `service_role`.

## Capability contract

The relay refuses to queue protocol commands unless the online agent advertises the matching capability in its authenticated heartbeat. The current Android TV implementation advertises only:

- `network-discovery`
- `tcp-probe`
- `http`
- `wake-on-lan`

ADB, SSDP, mDNS, ONVIF camera controls, and RTSP inspection remain unavailable until the TV agent implements and advertises them. Do not add a capability to the heartbeat merely to make a tool appear enabled.

Commands expire after three minutes. Queue claiming is atomic, command results are accepted only for the executing agent, and sensitive command payload fields are redacted after execution or expiry.
