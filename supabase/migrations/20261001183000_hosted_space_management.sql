-- Control-plane metadata only. Application entities never enter this database.
create table public.hosted_space_invitations (
  id uuid primary key default gen_random_uuid(),
  space_id uuid not null references public.hosted_spaces(id) on delete cascade,
  email text not null check (length(email) between 3 and 254),
  role text not null check (role in ('admin', 'member')),
  token_hash text not null unique,
  created_by uuid not null references auth.users(id),
  expires_at timestamptz not null default now() + interval '7 days',
  accepted_at timestamptz,
  revoked_at timestamptz
);
alter table public.hosted_space_invitations enable row level security;
revoke all on public.hosted_space_invitations from public, anon, authenticated;
grant select, insert, update, delete on public.hosted_space_invitations to service_role;

-- Explicit one-space selection after losing Gold. No entity or billing mirror data.
create table public.hosted_space_selections (
  user_id uuid primary key references auth.users(id) on delete cascade,
  space_id uuid references public.hosted_spaces(id) on delete set null
);
alter table public.hosted_space_selections enable row level security;
revoke all on public.hosted_space_selections from public,anon,authenticated;
grant select,insert,update,delete on public.hosted_space_selections to service_role;

create or replace function public.manage_hosted_space(
  p_actor uuid, p_action text, p_space uuid default null,
  p_name text default null, p_kind text default null, p_email text default null,
  p_role text default null, p_target uuid default null, p_invitation uuid default null,
  p_token_hash text default null, p_membership_limit integer default 1
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  active_space uuid;
  actor_role text;
  target_role text;
  member_count integer;
  owner_count integer;
  invite public.hosted_space_invitations;
  result jsonb;
  limited_space uuid;
  needs_selection boolean;
begin
  if not exists (select 1 from auth.users where id = p_actor) then
    raise exception 'Account unavailable' using errcode = '42501';
  end if;
  if p_action not in ('list','create','select','invite','accept','decline','revoke_invitation','role','remove','leave') then
    raise exception 'Unsupported space action' using errcode = '22023';
  end if;
  -- Serialize membership quotas and active selection for the purchasing account.
  perform pg_advisory_xact_lock(hashtextextended(p_actor::text, 1));
  select household_id into active_space from public.user_profiles where user_id = p_actor;
  if p_space is not null then active_space := p_space; end if;
  select count(*) into member_count from public.hosted_space_memberships where user_id = p_actor;
  if p_membership_limit is null then
    delete from public.hosted_space_selections where user_id=p_actor;
  elsif member_count<=p_membership_limit and exists(select 1 from public.hosted_space_memberships where user_id=p_actor and space_id=active_space) then
    insert into public.hosted_space_selections(user_id,space_id) values(p_actor,active_space)
      on conflict(user_id) do update set space_id=excluded.space_id;
  end if;
  select space_id into limited_space from public.hosted_space_selections where user_id=p_actor;
  if p_action in ('invite','revoke_invitation','role','remove') and p_membership_limit is not null and member_count>p_membership_limit and limited_space is distinct from active_space then
    raise exception 'Choose one active finance space after your Gold subscription ends' using errcode='23514';
  end if;
  if p_action = 'create' then
    if p_membership_limit is not null and member_count >= p_membership_limit then
      raise exception 'Your plan allows one finance space. Leave a space or upgrade to Gold.' using errcode = '23514';
    end if;
    if p_name is null or length(btrim(p_name)) not between 1 and 80 or p_kind not in ('home','business','other') then
      raise exception 'Enter a name and a valid space kind' using errcode = '22023';
    end if;
    active_space := gen_random_uuid();
    insert into public.hosted_spaces(id,name,kind) values(active_space,btrim(p_name),p_kind);
    insert into public.hosted_space_memberships(space_id,user_id,role) values(active_space,p_actor,'owner');
    update public.user_profiles set household_id = active_space where user_id = p_actor;
  elsif p_action in ('accept','decline') then
    select * into invite from public.hosted_space_invitations where token_hash = p_token_hash;
    if invite.id is not null then
      perform pg_advisory_xact_lock(hashtextextended(invite.space_id::text, 2));
      select * into invite from public.hosted_space_invitations where id = invite.id for update;
    end if;
    if invite.id is null or invite.accepted_at is not null or invite.revoked_at is not null or invite.expires_at <= now() then
      raise exception 'Invitation expired or unavailable' using errcode = '22023';
    end if;
    if not exists(select 1 from auth.users where id = p_actor and email_confirmed_at is not null and lower(email) = invite.email) then
      raise exception 'Sign in with the confirmed email invited to this space' using errcode = '42501';
    end if;
    if p_action = 'decline' then
      update public.hosted_space_invitations set revoked_at=now() where id=invite.id;
    else
      if not exists(select 1 from public.hosted_space_memberships where space_id=invite.space_id and user_id=p_actor) then
        if p_membership_limit is not null and member_count >= p_membership_limit then
          raise exception 'Leave your current space or upgrade to Gold before accepting.' using errcode = '23514';
        end if;
        -- Match lock order with owner operations on this space.
        perform pg_advisory_xact_lock(hashtextextended(invite.space_id::text, 2));
        insert into public.hosted_space_memberships(space_id,user_id,role) values(invite.space_id,p_actor,invite.role);
      end if;
      update public.hosted_space_invitations set accepted_at=now() where id=invite.id;
      active_space := invite.space_id;
      update public.user_profiles set household_id=active_space where user_id=p_actor;
    end if;
  end if;
  if p_action in ('select','invite','revoke_invitation','role','remove','leave') then
    perform pg_advisory_xact_lock(hashtextextended(active_space::text, 2));
    select role into actor_role from public.hosted_space_memberships where space_id=active_space and user_id=p_actor;
    if actor_role is null then raise exception 'Space membership required' using errcode='42501'; end if;
    if p_action = 'select' then
      -- Downgraded accounts explicitly choose one active space without deleting local books.
      update public.user_profiles set household_id=active_space where user_id=p_actor;
      if p_membership_limit is not null then
        insert into public.hosted_space_selections(user_id,space_id) values(p_actor,active_space)
          on conflict(user_id) do update set space_id=excluded.space_id;
      end if;
    elsif p_action in ('invite','revoke_invitation') then
      if actor_role not in ('owner','admin') then raise exception 'Owner or admin role required' using errcode='42501'; end if;
      if p_action='invite' then
        if p_email is null or length(btrim(p_email)) not between 3 and 254 or position('@' in p_email)<2
            or p_token_hash is null or length(p_token_hash)<>64 or p_role not in ('admin','member') then
          raise exception 'Invalid invitation' using errcode='22023';
        end if;
        if p_role='admin' and actor_role<>'owner' then raise exception 'Only an owner can invite an admin' using errcode='42501'; end if;
        if (select count(*) from public.hosted_space_invitations where space_id=active_space and expires_at>now() and revoked_at is null and accepted_at is null)>=25 then
          raise exception 'Revoke an existing invitation before creating another' using errcode='23514';
        end if;
        insert into public.hosted_space_invitations(space_id,email,role,token_hash,created_by)
          values(active_space,lower(btrim(p_email)),p_role,p_token_hash,p_actor);
      else
        update public.hosted_space_invitations set revoked_at=now() where id=p_invitation and space_id=active_space and accepted_at is null;
      end if;
    else
      if p_action='leave' then p_target:=p_actor; end if;
      select role into target_role from public.hosted_space_memberships where space_id=active_space and user_id=p_target;
      if target_role is null then raise exception 'Member unavailable' using errcode='22023'; end if;
      if p_action='role' and (actor_role<>'owner' or p_role not in ('owner','admin','member')) then
        raise exception 'Only an owner can change member roles' using errcode='42501';
      end if;
      if p_action='remove' and (actor_role not in ('owner','admin') or (actor_role='admin' and target_role<>'member')) then
        raise exception 'You cannot remove this member' using errcode='42501';
      end if;
      select count(*) into owner_count from public.hosted_space_memberships where space_id=active_space and role='owner';
      select count(*) into member_count from public.hosted_space_memberships where space_id=active_space;
      if target_role='owner' and owner_count=1 and (p_action<>'role' or p_role<>'owner') then
        if p_action='leave' and member_count=1 then
          -- A sole member may close the server-owned empty membership container.
          -- Local books remain on the device; no application data is deleted here.
          delete from public.hosted_space_invitations where space_id=active_space;
          delete from public.hosted_devices where household_id=active_space;
          delete from public.hosted_spaces where id=active_space;
          active_space:=null;
        else
          raise exception 'Promote another owner before leaving or removing the last owner' using errcode='23514';
        end if;
      elsif p_action='role' then
        update public.hosted_space_memberships set role=p_role where space_id=active_space and user_id=p_target;
      else
        delete from public.hosted_space_memberships where space_id=active_space and user_id=p_target;
        delete from public.hosted_devices where household_id=active_space and owner_user_id=p_target;
        if p_target=p_actor then active_space:=null; end if;
      end if;
    end if;
  end if;
  if not exists(select 1 from public.hosted_space_memberships where user_id=p_actor and space_id=active_space) then active_space:=null; end if;
  select count(*) into member_count from public.hosted_space_memberships where user_id=p_actor;
  select space_id into limited_space from public.hosted_space_selections where user_id=p_actor;
  needs_selection := p_membership_limit is not null and member_count>p_membership_limit and not exists(
    select 1 from public.hosted_space_memberships where user_id=p_actor and space_id=limited_space
  );
  if needs_selection or (p_membership_limit is not null and member_count>p_membership_limit and limited_space is distinct from active_space) then active_space:=null; end if;
  select jsonb_build_object(
    'requiresSpaceSelection',needs_selection,
    'activeSpaceId',active_space,
    'spaces',coalesce((select jsonb_agg(jsonb_build_object('id',s.id,'name',s.name,'kind',s.kind,'role',m.role) order by s.name,s.id)
      from public.hosted_space_memberships m join public.hosted_spaces s on s.id=m.space_id where m.user_id=p_actor),'[]'::jsonb),
    'members',coalesce((select jsonb_agg(jsonb_build_object('userId',m.user_id,'email',u.email,'role',m.role) order by m.joined_at,m.user_id)
      from public.hosted_space_memberships m join auth.users u on u.id=m.user_id where m.space_id=active_space),'[]'::jsonb),
    'invitations',case when exists(select 1 from public.hosted_space_memberships where user_id=p_actor and space_id=active_space and role in ('owner','admin')) then
      coalesce((select jsonb_agg(jsonb_build_object('id',i.id,'email',i.email,'role',i.role,'expiresAt',i.expires_at) order by i.expires_at)
        from public.hosted_space_invitations i where i.space_id=active_space and i.accepted_at is null and i.revoked_at is null and i.expires_at>now()),'[]'::jsonb)
      else '[]'::jsonb end
  ) into result;
  return result;
end;
$$;
revoke all on function public.manage_hosted_space(uuid,text,uuid,text,text,text,text,uuid,uuid,text,integer) from public,anon,authenticated;
grant execute on function public.manage_hosted_space(uuid,text,uuid,text,text,text,text,uuid,uuid,text,integer) to service_role;

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

  -- Match membership changes before locking device quotas.
  perform pg_advisory_xact_lock(hashtextextended(p_household_id::text, 2));
  if not exists(select 1 from public.hosted_space_memberships where space_id=p_household_id and user_id=p_user_id) then return false; end if;
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
      set last_seen_at = now(), owner_user_id = coalesce(owner_user_id, p_user_id)
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
