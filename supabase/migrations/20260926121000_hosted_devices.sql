-- Apply in the hosted Supabase project before enabling device enforcement.
create table if not exists public.hosted_devices (
  household_id uuid not null,
  device_id text not null check (length(device_id) between 1 and 128),
  registered_at timestamptz not null default now(),
  primary key (household_id, device_id)
);

alter table public.hosted_devices enable row level security;
revoke all on public.hosted_devices from anon, authenticated;
grant select, insert, delete on public.hosted_devices to service_role;

create or replace function public.register_hosted_device(
  p_household_id uuid,
  p_device_id text,
  p_device_limit integer
) returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  current_count integer;
begin
  if p_household_id is null or p_device_id is null or length(p_device_id) not between 1 and 128 then
    return false;
  end if;
  -- Serialize registrations per household, including concurrent joins on separate server instances.
  perform pg_advisory_xact_lock(hashtextextended(p_household_id::text, 0));
  select count(*) into current_count from public.hosted_devices where household_id = p_household_id;
  if p_device_limit is not null and current_count > p_device_limit then
    return false;
  end if;
  if exists (
    select 1 from public.hosted_devices
    where household_id = p_household_id and device_id = p_device_id
  ) then
    return true;
  end if;
  if p_device_limit is not null and current_count >= p_device_limit then
    return false;
  end if;
  insert into public.hosted_devices(household_id, device_id) values (p_household_id, p_device_id);
  return true;
end;
$$;

revoke all on function public.register_hosted_device(uuid, text, integer) from public, anon, authenticated;
grant execute on function public.register_hosted_device(uuid, text, integer) to service_role;
