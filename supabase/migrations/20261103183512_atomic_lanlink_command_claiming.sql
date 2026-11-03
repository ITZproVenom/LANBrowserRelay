create index if not exists lanlink_commands_expiry_idx
on public.lanlink_commands (expires_at)
where status in ('queued', 'running');

create or replace function public.lanlink_claim_commands(
  p_agent_id uuid,
  p_limit integer default 5
)
returns table (
  id uuid,
  kind text,
  payload jsonb,
  created_at timestamptz,
  expires_at timestamptz
)
language sql
security invoker
set search_path = ''
as $$
  with candidates as (
    select c.id
    from public.lanlink_commands as c
    where c.agent_id = p_agent_id
      and c.status = 'queued'
      and c.expires_at > now()
    order by c.created_at asc
    for update skip locked
    limit least(greatest(coalesce(p_limit, 5), 1), 5)
  ),
  claimed as (
    update public.lanlink_commands as c
    set status = 'running',
        updated_at = now()
    from candidates
    where c.id = candidates.id
    returning c.id, c.kind, c.payload, c.created_at, c.expires_at
  )
  select claimed.id, claimed.kind, claimed.payload, claimed.created_at, claimed.expires_at
  from claimed
  order by claimed.created_at asc;
$$;

revoke all on function public.lanlink_claim_commands(uuid, integer) from public;
revoke all on function public.lanlink_claim_commands(uuid, integer) from anon, authenticated;
grant execute on function public.lanlink_claim_commands(uuid, integer) to service_role;
