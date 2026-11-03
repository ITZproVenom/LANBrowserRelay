import { createClient } from "npm:@supabase/supabase-js@2.95.0";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-lanlink-agent, content-type, accept, mcp-protocol-version",
  "Access-Control-Allow-Methods": "POST, GET, OPTIONS",
  "Content-Type": "application/json; charset=utf-8",
};

const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
const secretMap = Deno.env.get("SUPABASE_SECRET_KEYS");
const serviceKey = secretMap ? JSON.parse(secretMap)["default"] : Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
if (!supabaseUrl || !serviceKey) throw new Error("Supabase server credentials are unavailable");
const db = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });

function json(data: unknown, status = 200, headers: Record<string,string> = {}) {
  return new Response(JSON.stringify(data), { status, headers: { ...cors, ...headers } });
}
function rpcError(id: unknown, code: number, message: string) {
  return json({ jsonrpc: "2.0", id: id ?? null, error: { code, message } }, 200, { "Mcp-Protocol-Version": "2025-03-26" });
}
async function sha256(value: string) {
  const bytes = new TextEncoder().encode(value);
  const hash = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(hash)).map((b) => b.toString(16).padStart(2,"0")).join("");
}
function randomAgentToken() {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return Array.from(bytes).map((value) => value.toString(16).padStart(2, "0")).join("");
}

function bearer(req: Request) {
  const h = req.headers.get("authorization") || "";
  return h.startsWith("Bearer ") ? h.slice(7).trim() : "";
}
function privateIPv4(ip: string) {
  const p = ip.split(".").map(Number);
  if (p.length !== 4 || p.some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return false;
  return p[0] === 10 || (p[0] === 172 && p[1] >= 16 && p[1] <= 31) || (p[0] === 192 && p[1] === 168);
}
function validMac(mac: string) {
  return /^([0-9a-f]{2}:){5}[0-9a-f]{2}$/i.test(mac);
}
async function authMcp(req: Request) {
  const token = bearer(req);
  if (!token || token.length > 256) return false;
  const hash = await sha256(token);
  const { data, error } = await db.from("lanlink_api_keys").select("id").eq("key_hash", hash).eq("active", true).maybeSingle();
  return !error && !!data;
}
async function authAgent(req: Request) {
  const token = bearer(req) || (req.headers.get("x-lanlink-agent") || "");
  if (!token || token.length > 256) return null;
  const hash = await sha256(token);
  const { data, error } = await db.from("lanlink_agents").select("id,name,enabled").eq("token_hash", hash).eq("enabled", true).maybeSingle();
  return error ? null : data;
}
async function queue(agentId: string, kind: string, payload: Record<string, unknown>) {
  const { data, error } = await db.from("lanlink_commands").insert({
    agent_id: agentId, kind, payload, status: "queued",
    expires_at: new Date(Date.now() + 180000).toISOString(),
  }).select("id,created_at").single();
  if (error) throw error;
  return data;
}
async function onlineAgent(agentId?: string) {
  const cutoff = new Date(Date.now() - 120000).toISOString();
  let q = db.from("lanlink_agents")
    .select("id,name,last_seen,enabled,capabilities,agent_version")
    .eq("enabled", true)
    .gt("last_seen", cutoff);
  if (agentId) q = q.eq("id", agentId);
  const { data, error } = await q.order("last_seen", { ascending: false }).limit(1).maybeSingle();
  if (error) throw error;
  if (!data) throw new Error("No online LAN bridge is available. Start the Android TV bridge and retry.");
  return data;
}
const allowedAgentCapabilities = new Set([
  "network-discovery", "tcp-probe", "http", "http-auth-basic", "wake-on-lan",
  "ssdp-discovery", "mdns-discovery", "onvif-discovery", "onvif-control",
  "rtsp-probe", "adb-control"
]);
function requireCapability(agent: any, capability: string) {
  const caps = Array.isArray(agent.capabilities) ? agent.capabilities : [];
  if (!caps.includes(capability)) {
    throw new Error(`The connected bridge build does not advertise ${capability}. Upgrade the Android TV bridge before using this control.`);
  }
}
function redactSecrets(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(redactSecrets);
  if (value && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
      const lower = key.toLowerCase();
      if (/(password|authorization|credential|secret|token|private.?key)/.test(lower) || lower === "body") {
        out[key] = "[redacted after execution]";
      } else {
        out[key] = redactSecrets(item);
      }
    }
    return out;
  }
  return value;
}
function textResult(value: unknown) {
  return { content: [{ type: "text", text: JSON.stringify(value) }] };
}
const tools = [
  { name: "lan_status", description: "Show the paired LAN bridge status and last contact. Use this before issuing device commands.", inputSchema: { type: "object", properties: {}, additionalProperties: false } },
  { name: "lan_devices", description: "List devices discovered on the bridge's local Wi-Fi/Ethernet subnet, including IP, MAC, hostname and observed open services.", inputSchema: { type: "object", properties: { agent_id: { type: "string", description: "Optional bridge ID from lan_status." } }, additionalProperties: false } },
  { name: "lan_discover", description: "Discover reachable devices on the bridge's local subnet (bounded to one /24 or smaller scan window and common TCP ports). This is read-only.", inputSchema: { type: "object", properties: { agent_id: { type: "string" } }, additionalProperties: false } },
  { name: "lan_probe", description: "Probe common service ports on one private IPv4 address in the bridge's own subnet. Read-only.", inputSchema: { type: "object", properties: { ip: { type: "string", description: "Private IPv4 address on the local subnet." } }, required: ["ip"], additionalProperties: false } },
  { name: "lan_protocol_discover", description: "Discover local services using SSDP/UPnP, mDNS/Bonjour or ONVIF WS-Discovery. Multicast scope is limited to the bridge's LAN.", inputSchema: { type: "object", properties: { protocol: { type: "string", enum: ["ssdp","mdns","onvif"] } }, required: ["protocol"], additionalProperties: false } },
  { name: "lan_adb_command", description: "Control an Android device on the same LAN through already-authorized ADB. Supports device info, app listing, navigation, media, volume, power, app launch/stop and reboot. ADB authorization must already exist on the target.", inputSchema: { type: "object", properties: { ip: { type: "string" }, action: { type: "string", enum: ["info","apps","home","back","power","volume_up","volume_down","mute","play_pause","next","previous","dpad_up","dpad_down","dpad_left","dpad_right","select","launch_app","stop_app","reboot"] }, package: { type: "string" } }, required: ["ip","action"], additionalProperties: false } },
  { name: "lan_camera_control", description: "Use ONVIF on a local camera: device information, capabilities, media profiles, snapshot URI and PTZ move/stop. Camera credentials are accepted for this one command and removed from the command queue after execution.", inputSchema: { type: "object", properties: { ip: { type: "string" }, port: { type: "integer", minimum: 1, maximum: 65535 }, action: { type: "string", enum: ["device_info","capabilities","profiles","snapshot_uri","ptz_move","ptz_stop"] }, endpoint: { type: "string" }, media_endpoint: { type: "string" }, ptz_endpoint: { type: "string" }, profile_token: { type: "string" }, username: { type: "string" }, password: { type: "string" }, pan: { type: "number", minimum: -1, maximum: 1 }, tilt: { type: "number", minimum: -1, maximum: 1 }, zoom: { type: "number", minimum: -1, maximum: 1 }, duration_seconds: { type: "number", minimum: 0.2, maximum: 3 } }, required: ["ip","action"], additionalProperties: false } },
  { name: "lan_rtsp_probe", description: "Inspect a local RTSP endpoint using OPTIONS and DESCRIBE. Returns authentication status and SDP metadata, not a playable video relay.", inputSchema: { type: "object", properties: { ip: { type: "string" }, port: { type: "integer", minimum: 1, maximum: 65535 }, path: { type: "string" }, username: { type: "string" }, password: { type: "string" } }, required: ["ip"], additionalProperties: false } },
  { name: "lan_http_request", description: "Send a bounded HTTP(S) request to a local device on the bridge's subnet. Supports custom headers or one-shot Basic credentials for device APIs. Only local RFC1918 destinations are allowed; configuration-changing calls require explicit user confirmation first.", inputSchema: { type: "object", properties: { ip: { type: "string" }, port: { type: "integer", minimum: 1, maximum: 65535 }, scheme: { type: "string", enum: ["http","https"] }, method: { type: "string", enum: ["GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS"] }, path: { type: "string", description: "Origin-form path beginning with /; no absolute URLs." }, content_type: { type: "string", enum: ["application/json","text/plain","application/xml","text/xml","application/soap+xml","application/x-www-form-urlencoded"] }, body: { type: "string", maxLength: 256000 }, headers: { type: "object", additionalProperties: { type: "string" } }, username: { type: "string" }, password: { type: "string" } }, required: ["ip","port","scheme","method","path"], additionalProperties: false } },
  { name: "lan_wake_device", description: "Send a Wake-on-LAN magic packet to a device MAC address on the local subnet. Read-only with respect to stored data; it wakes the target device.", inputSchema: { type: "object", properties: { mac: { type: "string" }, broadcast: { type: "string", description: "Optional subnet broadcast IPv4 address." } }, required: ["mac"], additionalProperties: false } },
  { name: "lan_command_status", description: "Read the result of a queued LAN bridge command.", inputSchema: { type: "object", properties: { command_id: { type: "string" } }, required: ["command_id"], additionalProperties: false } },
];

async function handleTool(name: string, args: Record<string, unknown>) {
  if (name === "lan_status") {
    const { data: agents, error: ae } = await db.from("lanlink_agents").select("id,name,enabled,last_seen,last_ip,capabilities,agent_version").order("created_at");
    if (ae) throw ae;
    const { count, error: ce } = await db.from("lanlink_devices").select("*", { count: "exact", head: true });
    if (ce) throw ce;
    return { agents: agents || [], discovered_device_count: count || 0, bridge_online: !!agents?.some((a:any) => a.enabled && a.last_seen && Date.now() - new Date(a.last_seen).getTime() < 120000) };
  }
  if (name === "lan_devices") {
    const { data, error } = await db.from("lanlink_devices").select("ip,mac,hostname,vendor,services,last_seen,agent_id").order("last_seen", { ascending: false }).limit(512);
    if (error) throw error;
    return { devices: data || [] };
  }
  if (name === "lan_discover") {
    const agent = await onlineAgent(typeof args.agent_id === "string" ? args.agent_id : undefined);
    requireCapability(agent, "network-discovery");
    return { queued: true, ...(await queue(agent.id, "discover", {})), agent: agent.name, note: "Discovery is limited to the bridge's own local subnet." };
  }
  if (name === "lan_probe") {
    const ip = String(args.ip || "");
    if (!privateIPv4(ip)) throw new Error("Only RFC1918 private IPv4 targets are allowed.");
    const agent = await onlineAgent();
    requireCapability(agent, "tcp-probe");
    return { queued: true, ...(await queue(agent.id, "probe", { ip })), target: ip };
  }
  if (name === "lan_protocol_discover") {
    const protocol = String(args.protocol || "");
    const mapping: Record<string, { kind: string; capability: string; payload: Record<string, unknown> }> = {
      ssdp: { kind: "ssdp", capability: "ssdp-discovery", payload: {} },
      mdns: { kind: "mdns", capability: "mdns-discovery", payload: {} },
      onvif: { kind: "onvif", capability: "onvif-discovery", payload: { action: "discover" } },
    };
    const selected = mapping[protocol];
    if (!selected) throw new Error("Choose ssdp, mdns or onvif.");
    const agent = await onlineAgent();
    requireCapability(agent, selected.capability);
    return { queued: true, ...(await queue(agent.id, selected.kind, selected.payload)), agent: agent.name, protocol };
  }
  if (name === "lan_adb_command") {
    const ip = String(args.ip || "");
    if (!privateIPv4(ip)) throw new Error("Only RFC1918 private IPv4 targets are allowed.");
    const action = String(args.action || "");
    const allowed = ["info","apps","home","back","power","volume_up","volume_down","mute","play_pause","next","previous","dpad_up","dpad_down","dpad_left","dpad_right","select","launch_app","stop_app","reboot"];
    if (!allowed.includes(action)) throw new Error("Unsupported ADB action.");
    const payload: Record<string, unknown> = { ip, action };
    if (args.package !== undefined) payload.package = String(args.package);
    const agent = await onlineAgent();
    requireCapability(agent, "adb-control");
    return { queued: true, ...(await queue(agent.id, "adb", payload)), agent: agent.name, target: ip, action };
  }
  if (name === "lan_camera_control") {
    const ip = String(args.ip || "");
    if (!privateIPv4(ip)) throw new Error("Only RFC1918 private IPv4 targets are allowed.");
    const action = String(args.action || "device_info");
    const allowed = ["device_info","capabilities","profiles","snapshot_uri","ptz_move","ptz_stop"];
    if (!allowed.includes(action)) throw new Error("Unsupported ONVIF camera action.");
    const username = args.username;
    const password = args.password;
    if ((username === undefined) !== (password === undefined)) throw new Error("Supply both camera username and password.");
    if (username !== undefined && (typeof username !== "string" || typeof password !== "string" || username.length > 128 || password.length > 256)) throw new Error("Camera credentials exceed allowed limits.");
    const payload: Record<string, unknown> = { ...args, ip, action };
    const agent = await onlineAgent();
    requireCapability(agent, "onvif-control");
    return { queued: true, ...(await queue(agent.id, "onvif", payload)), agent: agent.name, target: ip, action, credentials_queued_temporarily: username !== undefined, scrubbed_after_execution: true };
  }
  if (name === "lan_rtsp_probe") {
    const ip = String(args.ip || "");
    if (!privateIPv4(ip)) throw new Error("Only RFC1918 private IPv4 targets are allowed.");
    const port = args.port === undefined ? 554 : Number(args.port);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid RTSP port.");
    const username = args.username;
    const password = args.password;
    if ((username === undefined) !== (password === undefined)) throw new Error("Supply both RTSP username and password.");
    if (username !== undefined && (typeof username !== "string" || typeof password !== "string" || username.length > 128 || password.length > 256)) throw new Error("RTSP credentials exceed allowed limits.");
    const payload: Record<string, unknown> = { ip, port, path: typeof args.path === "string" ? args.path : "/" };
    if (username !== undefined) { payload.username = username; payload.password = password; }
    const agent = await onlineAgent();
    requireCapability(agent, "rtsp-probe");
    return { queued: true, ...(await queue(agent.id, "rtsp", payload)), agent: agent.name, target: ip, port };
  }
  if (name === "lan_http_request") {
    const ip = String(args.ip || "");
    if (!privateIPv4(ip)) throw new Error("Only RFC1918 private IPv4 targets are allowed.");
    const port = Number(args.port);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid TCP port.");
    const scheme = String(args.scheme || "http");
    if (!["http","https"].includes(scheme)) throw new Error("Only HTTP and HTTPS are allowed.");
    const method = String(args.method || "GET").toUpperCase();
    if (!["GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS"].includes(method)) throw new Error("HTTP method not allowed.");
    const path = String(args.path || "");
    if (!path.startsWith("/") || path.startsWith("//") || path.includes("\\") || /[\r\n]/.test(path) || path.length > 2048 || path.split("/").includes("..")) throw new Error("Provide a safe origin-form path beginning with /.");
    const body = typeof args.body === "string" ? args.body : "";
    if (new TextEncoder().encode(body).length > 256000) throw new Error("Body exceeds 256 KB.");
    const ct = String(args.content_type || "application/json");
    if (!["application/json","text/plain","application/xml","text/xml","application/soap+xml","application/x-www-form-urlencoded"].includes(ct)) throw new Error("Content type not allowed.");
    const headers = args.headers === undefined ? {} : args.headers;
    if (!headers || typeof headers !== "object" || Array.isArray(headers) || Object.keys(headers).length > 12) throw new Error("Headers must be an object with at most 12 entries.");
    const forbidden = new Set(["host","connection","content-length","transfer-encoding","proxy-authorization","proxy-connection"]);
    for (const [key,value] of Object.entries(headers)) {
      if (!/^[A-Za-z0-9-]{1,64}$/.test(key) || forbidden.has(key.toLowerCase()) || typeof value !== "string" || value.length > 2048 || /[\r\n]/.test(value)) throw new Error("Unsafe or invalid HTTP header.");
    }
    const username = args.username;
    const password = args.password;
    if ((username === undefined) !== (password === undefined)) throw new Error("Supply both Basic authentication username and password.");
    if (username !== undefined && (typeof username !== "string" || typeof password !== "string" || username.length > 128 || password.length > 256)) throw new Error("HTTP credentials exceed allowed limits.");
    const agent = await onlineAgent();
    requireCapability(agent, "http");
    const payload: Record<string, unknown> = { ip, port, scheme, method, path, content_type: ct, body, headers };
    if (username !== undefined) { payload.username = username; payload.password = password; }
    return { queued: true, ...(await queue(agent.id, "http", payload)), target: ip };
  }
  if (name === "lan_wake_device") {
    const mac = String(args.mac || "");
    if (!validMac(mac)) throw new Error("Invalid MAC address.");
    const agent = await onlineAgent();
    requireCapability(agent, "wake-on-lan");
    const broadcast = typeof args.broadcast === "string" && privateIPv4(args.broadcast) ? args.broadcast : undefined;
    return { queued: true, ...(await queue(agent.id, "wol", { mac, broadcast })), target_mac: mac };
  }
  if (name === "lan_command_status") {
    const id = String(args.command_id || "");
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(id)) {
      throw new Error("Invalid command ID.");
    }
    const now = new Date().toISOString();
    const { error: expiryError } = await db.from("lanlink_commands")
      .update({ status: "expired", updated_at: now, payload: { redacted: true } })
      .eq("id", id)
      .in("status", ["queued", "running"])
      .lte("expires_at", now);
    if (expiryError) throw expiryError;
    const { data, error } = await db.from("lanlink_commands")
      .select("id,kind,status,result,error,created_at,updated_at,expires_at")
      .eq("id", id)
      .maybeSingle();
    if (error) throw error;
    if (!data) throw new Error("Command not found.");
    return data;
  }
  throw new Error("Unknown tool.");
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });

  const url = new URL(req.url);
  const path = url.pathname;
  // A one-time high-entropy code bootstraps the TV agent without shipping
  // a long-lived relay token inside either mobile or TV app binaries.
  if (path.endsWith("/agent/enroll")) {
    if (req.method !== "POST") return json({ error: "POST required" }, 405);
    const body = await req.json().catch(() => ({}));
    const code = typeof body.code === "string" ? body.code.trim().toLowerCase() : "";
    if (!/^[0-9a-f]{64}$/.test(code)) {
      return json({ error: "Invalid or expired activation code." }, 400);
    }

    const codeHash = await sha256(code);
    const now = new Date().toISOString();
    const { data: invite, error: lookupError } = await db
      .from("lanlink_agent_enrollments")
      .select("id,agent_id")
      .eq("code_hash", codeHash)
      .is("consumed_at", null)
      .gt("expires_at", now)
      .maybeSingle();
    if (lookupError) return json({ error: "Activation lookup failed." }, 500);
    if (!invite) return json({ error: "Invalid or expired activation code." }, 401);

    // Conditional UPDATE makes the invite single-use even under concurrent calls.
    const { data: claimed, error: claimError } = await db
      .from("lanlink_agent_enrollments")
      .update({ consumed_at: now })
      .eq("id", invite.id)
      .is("consumed_at", null)
      .gt("expires_at", now)
      .select("agent_id")
      .maybeSingle();
    if (claimError) return json({ error: "Activation could not be claimed." }, 500);
    if (!claimed) return json({ error: "Activation code has already been used." }, 409);

    const agentToken = randomAgentToken();
    const { data: agent, error: agentError } = await db
      .from("lanlink_agents")
      .update({
        token_hash: await sha256(agentToken),
        enabled: true,
        last_seen: null,
        last_ip: null,
      })
      .eq("id", claimed.agent_id)
      .select("id,name")
      .single();
    if (agentError || !agent) {
      return json({ error: "Could not provision the Android TV agent." }, 500);
    }

    return json({
      agent_token: agentToken,
      agent_id: agent.id,
      agent_name: agent.name,
    }, 200, { "Cache-Control": "no-store" });
  }

  if (path.endsWith("/agent/poll") || path.endsWith("/agent/heartbeat") || path.endsWith("/agent/report")) {
    const agent = await authAgent(req);
    if (!agent) return json({ error: "Unauthorized bridge" }, 401);
    if (path.endsWith("/agent/heartbeat")) {
      const now = new Date().toISOString();
      const updates: Record<string, unknown> = { last_seen: now };
      if (req.method === "POST") {
        const body = await req.json().catch(() => ({}));
        if (body.capabilities !== undefined) {
          if (!Array.isArray(body.capabilities) || body.capabilities.length > 24 ||
              body.capabilities.some((item: unknown) => typeof item !== "string" || !allowedAgentCapabilities.has(item))) {
            return json({ error: "Invalid bridge capability report." }, 400);
          }
          updates.capabilities = [...new Set(body.capabilities)];
        }
        if (body.version !== undefined) {
          if (typeof body.version !== "string" || body.version.length > 32) return json({ error: "Invalid bridge version." }, 400);
          updates.agent_version = body.version;
        }
      } else if (req.method !== "GET") {
        return json({ error: "Use GET or POST for heartbeat." }, 405);
      }
      const { error } = await db.from("lanlink_agents").update(updates).eq("id", agent.id);
      if (error) return json({ error: "Agent heartbeat update failed." }, 500);
      return json({ ok: true, agent_id: agent.id });
    }
    await db.from("lanlink_agents").update({ last_seen: new Date().toISOString() }).eq("id", agent.id);
    if (path.endsWith("/agent/poll")) {
      const now = new Date().toISOString();
      const { error: expiryError } = await db.from("lanlink_commands")
        .update({ status: "expired", updated_at: now, payload: { redacted: true } })
        .eq("agent_id", agent.id)
        .in("status", ["queued", "running"])
        .lte("expires_at", now);
      if (expiryError) return json({ error: "Queue expiry update failed" }, 500);

      const { data: commands, error } = await db.rpc("lanlink_claim_commands", {
        p_agent_id: agent.id,
        p_limit: 5,
      });
      if (error) return json({ error: "Queue claim failed" }, 500);
      return json({ commands: commands || [], agent_id: agent.id });
    }
    if (req.method !== "POST") return json({ error: "POST required" }, 405);
    const body = await req.json().catch(() => ({}));
    let commandAccepted: boolean | null = null;
    if (body.command_id !== undefined && body.command_id !== null) {
      if (typeof body.command_id !== "string" ||
          !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(body.command_id)) {
        return json({ error: "Invalid command ID." }, 400);
      }
      const now = new Date().toISOString();
      const { data: previous, error: previousError } = await db.from("lanlink_commands")
        .select("payload")
        .eq("id", body.command_id)
        .eq("agent_id", agent.id)
        .eq("status", "running")
        .gt("expires_at", now)
        .maybeSingle();
      if (previousError) return json({ error: "Command lookup failed" }, 500);
      const updates: Record<string,unknown> = {
        status: body.error ? "failed" : "completed",
        result: body.result ?? null,
        error: typeof body.error === "string" ? body.error.slice(0,1000) : null,
        payload: redactSecrets(previous?.payload ?? {}),
        updated_at: now,
      };
      const { data: updated, error } = await db.from("lanlink_commands")
        .update(updates)
        .eq("id", body.command_id)
        .eq("agent_id", agent.id)
        .eq("status", "running")
        .gt("expires_at", now)
        .select("id")
        .maybeSingle();
      if (error) return json({ error: "Command result update failed" }, 500);
      commandAccepted = !!updated;
    }
    if (Array.isArray(body.devices)) {
      const rows = body.devices.slice(0,512).filter((d:any) => typeof d.ip === "string" && privateIPv4(d.ip)).map((d:any) => ({
        agent_id: agent.id,
        ip: d.ip,
        mac: typeof d.mac === "string" ? d.mac.slice(0,32) : null,
        hostname: typeof d.hostname === "string" ? d.hostname.slice(0,255) : null,
        vendor: typeof d.vendor === "string" ? d.vendor.slice(0,100) : null,
        services: Array.isArray(d.services) ? d.services.slice(0,64) : [],
        last_seen: new Date().toISOString(),
      }));
      if (rows.length) {
        const { error } = await db.from("lanlink_devices").upsert(rows, { onConflict: "agent_id,ip" });
        if (error) return json({ error: "Device report failed" }, 500);
      }
    }
    return json({ ok: true, ...(commandAccepted === null ? {} : { command_accepted: commandAccepted }) });
  }

  if (path.endsWith("/mcp")) {
    if (!await authMcp(req)) return json({ error: "Unauthorized MCP client" }, 401);
    if (req.method !== "POST") return json({ error: "Use POST for MCP requests" }, 405);
    let body: any;
    try { body = await req.json(); } catch { return rpcError(null, -32700, "Parse error"); }
    const id = body.id ?? null;
    const method = body.method;
    try {
      if (method === "notifications/initialized" || method === "notifications/cancelled") return new Response(null, { status: 202, headers: cors });
      if (method === "initialize") {
        return json({ jsonrpc: "2.0", id, result: { protocolVersion: body.params?.protocolVersion || "2025-03-26", capabilities: { tools: { listChanged: false } }, serverInfo: { name: "LANLink", version: "0.2.0" }, instructions: "LANLink securely relays local network discovery, authenticated device APIs, Android ADB controls, SSDP/mDNS/ONVIF discovery, ONVIF camera controls and RTSP inspection through a paired bridge. All targets are restricted to the owner's private LAN and each control is capability-gated." } }, 200, { "Mcp-Protocol-Version": "2025-03-26" });
      }
      if (method === "ping") return json({ jsonrpc: "2.0", id, result: {} });
      if (method === "tools/list") return json({ jsonrpc: "2.0", id, result: { tools } }, 200, { "Mcp-Protocol-Version": "2025-03-26" });
      if (method === "tools/call") {
        const name = String(body.params?.name || "");
        const args = body.params?.arguments && typeof body.params.arguments === "object" ? body.params.arguments : {};
        const result = await handleTool(name, args);
        return json({ jsonrpc: "2.0", id, result: textResult(result) }, 200, { "Mcp-Protocol-Version": "2025-03-26" });
      }
      return rpcError(id, -32601, "Method not found");
    } catch (e) {
      if (method === "tools/call") return json({ jsonrpc: "2.0", id, result: { content: [{ type: "text", text: (e as Error).message }], isError: true } }, 200, { "Mcp-Protocol-Version": "2025-03-26" });
      return rpcError(id, -32603, (e as Error).message);
    }
  }
  return json({ name: "LANLink bridge relay", status: "ok", endpoints: ["/mcp", "/agent/heartbeat", "/agent/poll", "/agent/report"] });
});
