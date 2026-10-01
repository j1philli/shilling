-- Minimal Supabase control-plane fixtures for isolated PostgreSQL policy tests.
create role anon;
create role authenticated;
create role service_role;
create schema auth;
create table auth.users(id uuid primary key, email text, email_confirmed_at timestamptz);
create table public.user_profiles(user_id uuid primary key references auth.users(id), household_id uuid not null, created_at timestamptz not null default now());
