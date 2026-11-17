-- In-app account deletion. Removes server-owned control-plane metadata for one account;
-- application entities never enter this database. The server deletes the Auth user through
-- the Auth admin API after this function succeeds, so a failed Auth call can be retried.

-- Invitations an account sent stay valid for the space after the sender is deleted.
alter table public.hosted_space_invitations alter column created_by drop not null;
alter table public.hosted_space_invitations
  drop constraint if exists hosted_space_invitations_created_by_fkey;
alter table public.hosted_space_invitations
  add constraint hosted_space_invitations_created_by_fkey
  foreign key (created_by) references auth.users(id) on delete set null;

create or replace function public.delete_hosted_account(p_actor uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare
  actor_email text;
  membership record;
  successor uuid;
begin
  select lower(email) into actor_email from auth.users where id = p_actor;
  if not found then
    raise exception 'Account unavailable' using errcode = '42501';
  end if;
  -- Match manage_hosted_space lock order: account first, then each space in a stable order.
  perform pg_advisory_xact_lock(hashtextextended(p_actor::text, 1));
  for membership in
    select space_id, role from public.hosted_space_memberships where user_id = p_actor order by space_id
  loop
    perform pg_advisory_xact_lock(hashtextextended(membership.space_id::text, 2));
    if not exists (select 1 from public.hosted_space_memberships
                   where space_id = membership.space_id and user_id <> p_actor) then
      -- Sole member: close the empty membership container, as 'leave' does.
      delete from public.hosted_space_invitations where space_id = membership.space_id;
      delete from public.hosted_devices where household_id = membership.space_id;
      delete from public.hosted_spaces where id = membership.space_id;
    else
      if membership.role = 'owner' and not exists (
          select 1 from public.hosted_space_memberships
          where space_id = membership.space_id and user_id <> p_actor and role = 'owner') then
        -- Never leave remaining members without an owner: promote the longest-standing admin,
        -- else the longest-standing member.
        select user_id into successor from public.hosted_space_memberships
          where space_id = membership.space_id and user_id <> p_actor
          order by case role when 'admin' then 0 else 1 end, joined_at, user_id
          limit 1;
        update public.hosted_space_memberships set role = 'owner'
          where space_id = membership.space_id and user_id = successor;
      end if;
      delete from public.hosted_devices
        where household_id = membership.space_id and owner_user_id = p_actor;
      delete from public.hosted_space_memberships
        where space_id = membership.space_id and user_id = p_actor;
    end if;
  end loop;
  if actor_email is not null then
    delete from public.hosted_space_invitations where lower(email) = actor_email;
  end if;
  delete from public.hosted_space_selections where user_id = p_actor;
  delete from public.auth_account_lookup_limits where user_id = p_actor;
  delete from public.user_profiles where user_id = p_actor;
end;
$$;

revoke all on function public.delete_hosted_account(uuid) from public, anon, authenticated;
grant execute on function public.delete_hosted_account(uuid) to service_role;
