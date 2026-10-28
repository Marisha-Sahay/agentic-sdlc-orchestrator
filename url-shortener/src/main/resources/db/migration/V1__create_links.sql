-- Short links. Rows are never physically deleted: deleted_at marks a soft delete so a code is
-- never handed out again. The unique constraint on code is what guarantees uniqueness of both
-- generated codes and custom aliases (inserts use ON CONFLICT (code) DO NOTHING).
create table links (
    id          bigint generated always as identity primary key,
    code        varchar(32)   not null,
    target_url  varchar(2048) not null,
    created_at  timestamptz   not null,
    expires_at  timestamptz,
    deleted_at  timestamptz,
    constraint uq_links_code unique (code),
    constraint ck_links_expiry_after_creation check (expires_at is null or expires_at > created_at)
);
