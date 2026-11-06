alter table public.lanlink_agents
  add column if not exists agent_version text;
