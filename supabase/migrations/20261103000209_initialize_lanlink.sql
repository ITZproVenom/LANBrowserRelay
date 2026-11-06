
create extension if not exists pgcrypto;

create table if not exists public.lanlink_api_keys (
  id text primary key,
  key_hash text not null,
  active boolean not null default true,
  created_at timestamptz not null default now()
);

create table if not exists public.lanlink_agents (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  token_hash text not null unique,
  enabled boolean not null default true,
  last_seen timestamptz,
  last_ip text,
  capabilities jsonb not null default '[]'::jsonb,
  created_at timestamptz not null default now()
);

create table if not exists public.lanlink_devices (
  agent_id uuid not null references public.lanlink_agents(id) on delete cascade,
  ip text not null,
  mac text,
  hostname text,
  vendor text,
  services jsonb not null default '[]'::jsonb,
  last_seen timestamptz not null default now(),
  primary key (agent_id, ip)
);

create table if not exists public.lanlink_commands (
  id uuid primary key default gen_random_uuid(),
  agent_id uuid not null references public.lanlink_agents(id) on delete cascade,
  kind text not null check (kind in ('discover','probe','http','wol','adb')),
  payload jsonb not null default '{}'::jsonb,
  status text not null default 'queued' check (status in ('queued','running','completed','failed','expired')),
  result jsonb,
  error text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  expires_at timestamptz not null default (now() + interval '3 minutes')
);

create index if not exists lanlink_commands_agent_status_idx
  on public.lanlink_commands(agent_id, status, created_at);

alter table public.lanlink_api_keys enable row level security;
alter table public.lanlink_agents enable row level security;
alter table public.lanlink_devices enable row level security;
alter table public.lanlink_commands enable row level security;

revoke all on public.lanlink_api_keys from anon, authenticated;
revoke all on public.lanlink_agents from anon, authenticated;
revoke all on public.lanlink_devices from anon, authenticated;
revoke all on public.lanlink_commands from anon, authenticated;

-- Privileged Edge Functions use service_role. No direct client access is granted.
grant all on public.lanlink_api_keys to service_role;
grant all on public.lanlink_agents to service_role;
grant all on public.lanlink_devices to service_role;
grant all on public.lanlink_commands to service_role;
