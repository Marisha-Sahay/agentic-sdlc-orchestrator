-- Supports the expired-link cleanup job: find links whose expiry has passed.
create index idx_links_expires_at on links (expires_at) where expires_at is not null;
