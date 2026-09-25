-- Public API access is limited to a user's own profile tier. The server uses
-- its secret key for the household lookup; clients never need that column.
alter table public.user_profiles enable row level security;
alter table public.waitlist enable row level security;

revoke all on public.user_profiles from anon, authenticated;
revoke all on public.waitlist from anon, authenticated;
grant select (user_id, tier) on public.user_profiles to authenticated;

create policy "Users can read their own profile tier"
on public.user_profiles for select to authenticated
using ((select auth.uid()) = user_id);

-- Trigger functions are invoked by database triggers, never through RPC.
revoke all on function public.handle_new_user_profile() from public, anon, authenticated;
revoke all on function public.rls_auto_enable() from public, anon, authenticated;

-- New public objects should not automatically become accessible through the
-- Data API. Grant each intended privilege explicitly in a later migration.
alter default privileges for role postgres in schema public
  revoke all on tables from anon, authenticated;
alter default privileges for role postgres in schema public
  revoke all on sequences from anon, authenticated;
alter default privileges for role postgres in schema public
  revoke all on functions from public, anon, authenticated;

-- Supabase Auth, rather than a client-controlled profile update, owns tier
-- promotion when an anonymous account gains an email address.
create or replace function public.handle_new_user_profile()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  insert into public.user_profiles (user_id, tier, email)
  values (
    new.id,
    case when new.email is null then 'anonymous' else 'free' end,
    new.email
  )
  on conflict (user_id) do update
    set email = excluded.email,
        tier = case when public.user_profiles.tier = 'paid'
          then 'paid' else excluded.tier end,
        updated_at = now();
  return new;
end;
$$;

create trigger on_auth_user_email_changed_profile
after update of email on auth.users
for each row when (old.email is distinct from new.email)
execute function public.handle_new_user_profile();
