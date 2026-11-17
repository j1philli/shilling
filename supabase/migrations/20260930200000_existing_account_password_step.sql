-- The email-first account flow needs to choose between a password field and a
-- sign-in email after Auth reports an existing address. Limit this lookup to
-- authenticated clients and cap probes per anonymous account.
create table public.auth_account_lookup_limits (
  user_id uuid primary key,
  window_started_at timestamptz not null,
  attempts integer not null
);

alter table public.auth_account_lookup_limits enable row level security;
revoke all on public.auth_account_lookup_limits from public, anon, authenticated;

create or replace function public.existing_account_has_password(account_email text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  lookup_count integer;
  password_exists boolean;
  now_at timestamptz := clock_timestamp();
begin
  if auth.uid() is null or account_email is null or length(account_email) > 254
    or position('@' in account_email) < 2 then
    raise exception 'Invalid account lookup';
  end if;

  insert into public.auth_account_lookup_limits as limits (user_id, window_started_at, attempts)
  values (auth.uid(), now_at, 1)
  on conflict (user_id) do update
    set window_started_at = case
      when limits.window_started_at <= now_at - interval '1 hour' then now_at
      else limits.window_started_at
    end,
    attempts = case
      when limits.window_started_at <= now_at - interval '1 hour' then 1
      else limits.attempts + 1
    end
  returning attempts into lookup_count;

  if lookup_count > 10 then
    return true;
  end if;

  select coalesce(users.encrypted_password, '') <> ''
  into password_exists
  from auth.users as users
  where lower(users.email) = lower(btrim(account_email))
  limit 1;

  -- Unknown addresses take the same path as password accounts. Supabase Auth
  -- determines whether the email can actually be upgraded or signed in.
  return coalesce(password_exists, true);
end;
$$;

revoke all on function public.existing_account_has_password(text) from public, anon;
grant execute on function public.existing_account_has_password(text) to authenticated;
