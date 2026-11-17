-- Keep password setup state on the Auth-owned profile so a returning guest
-- sees the next step even if local settings or the app session were reset.
alter table public.user_profiles
add column if not exists has_password boolean not null default false;

create or replace function public.handle_new_user_profile()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.user_profiles (user_id, tier, email, has_password)
  values (
    new.id,
    case
      when new.email is not null and new.email_confirmed_at is not null then 'free'
      else 'anonymous'
    end,
    new.email,
    coalesce(new.encrypted_password, '') <> ''
  )
  on conflict (user_id) do update
    set email = excluded.email,
        tier = case
          when public.user_profiles.tier = 'paid' then 'paid'
          else excluded.tier
        end,
        has_password = excluded.has_password,
        updated_at = now();
  return new;
end;
$$;

drop trigger if exists on_auth_user_email_changed_profile on auth.users;
create trigger on_auth_user_email_changed_profile
after update of email, email_confirmed_at, encrypted_password on auth.users
for each row
when (
  old.email is distinct from new.email
  or old.email_confirmed_at is distinct from new.email_confirmed_at
  or old.encrypted_password is distinct from new.encrypted_password
)
execute function public.handle_new_user_profile();

update public.user_profiles as profile
set has_password = coalesce(auth_user.encrypted_password, '') <> '',
    updated_at = now()
from auth.users as auth_user
where profile.user_id = auth_user.id
  and profile.has_password is distinct from (coalesce(auth_user.encrypted_password, '') <> '');

grant select (has_password) on public.user_profiles to authenticated;
