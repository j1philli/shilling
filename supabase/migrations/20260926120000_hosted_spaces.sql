-- Hosted metadata migration. Apply after the public metadata lockdown migration.
-- user_profiles.household_id remains the active space during client migration.
create table if not exists public.hosted_spaces (
  id uuid primary key,
  name text not null default 'Home',
  kind text not null default 'home' check (kind in ('home', 'business', 'other')),
  created_at timestamptz not null default now()
);

create table if not exists public.hosted_space_memberships (
  space_id uuid not null references public.hosted_spaces(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null check (role in ('owner', 'admin', 'member')),
  joined_at timestamptz not null default now(),
  primary key (space_id, user_id)
);
create index if not exists hosted_space_memberships_user_idx
  on public.hosted_space_memberships(user_id);

-- Preserve every existing household association. The oldest profile becomes
-- owner; other existing members retain membership without management rights.
insert into public.hosted_spaces(id)
select distinct household_id from public.user_profiles
on conflict (id) do nothing;

insert into public.hosted_space_memberships(space_id, user_id, role)
select household_id, user_id,
  case when row_number() over (
    partition by household_id order by created_at, user_id
  ) = 1 then 'owner' else 'member' end
from public.user_profiles
on conflict (space_id, user_id) do nothing;

create or replace function public.create_initial_hosted_space()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.hosted_spaces(id) values (new.household_id)
    on conflict (id) do nothing;
  insert into public.hosted_space_memberships(space_id, user_id, role)
    values (new.household_id, new.user_id, 'owner')
    on conflict (space_id, user_id) do nothing;
  return new;
end;
$$;
revoke all on function public.create_initial_hosted_space() from public, anon, authenticated;

drop trigger if exists create_initial_hosted_space on public.user_profiles;
create trigger create_initial_hosted_space
  after insert on public.user_profiles
  for each row execute function public.create_initial_hosted_space();

alter table public.hosted_spaces enable row level security;
alter table public.hosted_space_memberships enable row level security;
revoke all on public.hosted_spaces from public, anon, authenticated;
revoke all on public.hosted_space_memberships from public, anon, authenticated;
grant select, insert, update, delete on public.hosted_spaces to service_role;
grant select, insert, update, delete on public.hosted_space_memberships to service_role;
