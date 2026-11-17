-- Record which authenticated member registered each finance-space device.
-- Existing rows retain unknown ownership; an owner/admin may remove and re-register them.
alter table public.hosted_devices
  add column if not exists owner_user_id uuid references auth.users(id) on delete set null,
  add column if not exists last_seen_at timestamptz;

grant update on public.hosted_devices to service_role;

drop function if exists public.register_hosted_device(uuid, text, integer);

create or replace function public.register_hosted_device(
  p_household_id uuid,
  p_device_id text,
  p_device_limit integer,
  p_user_id uuid
) returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  current_count integer;
  registered_owner uuid;
begin
  if p_household_id is null or p_user_id is null or p_device_id is null
      or length(p_device_id) not between 1 and 128 then
    return false;
  end if;

  perform pg_advisory_xact_lock(hashtextextended(p_household_id::text, 0));
  select count(*) into current_count from public.hosted_devices where household_id = p_household_id;
  if p_device_limit is not null and current_count > p_device_limit then
    return false;
  end if;

  if exists (select 1 from public.hosted_devices
             where household_id = p_household_id and device_id = p_device_id) then
    select owner_user_id into registered_owner from public.hosted_devices
      where household_id = p_household_id and device_id = p_device_id;
    if registered_owner is not null and registered_owner <> p_user_id then
      return false;
    end if;
    update public.hosted_devices
      set last_seen_at = now()
      where household_id = p_household_id and device_id = p_device_id;
    return true;
  end if;

  if p_device_limit is not null and current_count >= p_device_limit then
    return false;
  end if;
  insert into public.hosted_devices(household_id, device_id, owner_user_id, last_seen_at)
    values (p_household_id, p_device_id, p_user_id, now());
  return true;
end;
$$;

revoke all on function public.register_hosted_device(uuid, text, integer, uuid)
  from public, anon, authenticated;
grant execute on function public.register_hosted_device(uuid, text, integer, uuid) to service_role;
