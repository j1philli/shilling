-- Profile tiers follow verified Supabase Auth state. Clients may read their own
-- tier, but only the auth trigger (or service role) may write it.
create or replace function public.handle_new_user_profile()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.user_profiles (user_id, tier, email)
  values (
    new.id,
    case
      when new.email is not null and new.email_confirmed_at is not null then 'free'
      else 'anonymous'
    end,
    new.email
  )
  on conflict (user_id) do update
    set email = excluded.email,
        tier = case
          when public.user_profiles.tier = 'paid' then 'paid'
          else excluded.tier
        end,
        updated_at = now();
  return new;
end;
$$;

drop trigger if exists on_auth_user_email_changed_profile on auth.users;
create trigger on_auth_user_email_changed_profile
after update of email, email_confirmed_at on auth.users
for each row
when (
  old.email is distinct from new.email
  or old.email_confirmed_at is distinct from new.email_confirmed_at
)
execute function public.handle_new_user_profile();

-- The existing RLS policy limits reads to auth.uid() = user_id. Grant only
-- the columns required by the client's tier lookup and filter.
grant select (user_id, tier) on public.user_profiles to authenticated;

-- Repair profiles that missed promotion because the old trigger only watched
-- email changes. This does not modify paid profiles or unverified users.
update public.user_profiles as profile
set tier = 'free', email = auth_user.email, updated_at = now()
from auth.users as auth_user
where profile.user_id = auth_user.id
  and profile.tier = 'anonymous'
  and auth_user.email is not null
  and auth_user.email_confirmed_at is not null;
