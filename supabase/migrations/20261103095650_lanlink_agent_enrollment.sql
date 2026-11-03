create table if not exists public.lanlink_agent_enrollments (
  id uuid primary key default gen_random_uuid(),
  agent_id uuid not null references public.lanlink_agents(id) on delete cascade,
  code_hash text not null unique check (code_hash ~ '^[0-9a-f]{64}$'),
  expires_at timestamptz not null,
  consumed_at timestamptz,
  created_at timestamptz not null default now()
);

alter table public.lanlink_agent_enrollments enable row level security;
revoke all on table public.lanlink_agent_enrollments from anon, authenticated;
grant all on table public.lanlink_agent_enrollments to service_role;

create index if not exists lanlink_agent_enrollments_pending_idx
  on public.lanlink_agent_enrollments (expires_at)
  where consumed_at is null;
