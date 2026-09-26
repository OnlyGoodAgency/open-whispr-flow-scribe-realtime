-- OpenWhisperFlow shared account and transcription history schema.
-- Audio is deliberately not persisted; only the resulting text and metadata sync.

create table public.profiles (
    id uuid primary key references auth.users (id) on delete cascade,
    display_name text check (display_name is null or char_length(display_name) <= 100),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table public.transcription_history (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    client_entry_id text not null check (char_length(client_entry_id) between 1 and 128),
    device_id text not null check (char_length(device_id) between 1 and 128),
    platform text not null check (platform in ('android', 'windows', 'macos', 'linux', 'unknown')),
    transcription_text text not null check (char_length(transcription_text) > 0),
    post_processed_text text,
    post_process_prompt text,
    post_process_requested boolean not null default false,
    requested_language text not null default 'auto',
    duration_ms bigint not null default 0 check (duration_ms >= 0),
    model_key text not null default '',
    provider text not null default '',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint transcription_history_device_entry_unique
        unique (user_id, device_id, client_entry_id)
);

create index transcription_history_user_created_idx
    on public.transcription_history (user_id, created_at desc);

create index transcription_history_user_active_idx
    on public.transcription_history (user_id, updated_at desc)
    where deleted_at is null;

alter table public.profiles enable row level security;
alter table public.transcription_history enable row level security;

revoke all on table public.profiles from anon;
revoke all on table public.transcription_history from anon;

grant usage on schema public to authenticated;
grant select, insert, update, delete on table public.profiles to authenticated;
grant select, insert, update, delete on table public.transcription_history to authenticated;

create policy "profiles_select_own"
    on public.profiles
    for select
    to authenticated
    using ((select auth.uid()) = id);

create policy "profiles_insert_own"
    on public.profiles
    for insert
    to authenticated
    with check ((select auth.uid()) = id);

create policy "profiles_update_own"
    on public.profiles
    for update
    to authenticated
    using ((select auth.uid()) = id)
    with check ((select auth.uid()) = id);

create policy "profiles_delete_own"
    on public.profiles
    for delete
    to authenticated
    using ((select auth.uid()) = id);

create policy "transcription_history_select_own"
    on public.transcription_history
    for select
    to authenticated
    using ((select auth.uid()) = user_id);

create policy "transcription_history_insert_own"
    on public.transcription_history
    for insert
    to authenticated
    with check ((select auth.uid()) = user_id);

create policy "transcription_history_update_own"
    on public.transcription_history
    for update
    to authenticated
    using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

create policy "transcription_history_delete_own"
    on public.transcription_history
    for delete
    to authenticated
    using ((select auth.uid()) = user_id);
