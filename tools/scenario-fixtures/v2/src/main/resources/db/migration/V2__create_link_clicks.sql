-- One row per successful redirect, written in batches by the click recorder (best effort).
-- Privacy: no IP address, no user-agent string, no referrer path or query; only the referrer
-- host and a coarse agent family. Rows older than the retention period (90 days by default)
-- are deleted by a scheduled purge. Clicks reference the code, which is never reused.
create table link_clicks (
    id             bigint generated always as identity primary key,
    code           varchar(32)  not null,
    clicked_at     timestamptz  not null,
    referrer_host  varchar(255),
    agent_family   varchar(16)  not null,
    constraint ck_link_clicks_agent_family check (agent_family in ('BROWSER', 'BOT', 'OTHER'))
);

-- Statistics of one link over a time window.
create index idx_link_clicks_code_clicked_at on link_clicks (code, clicked_at);
-- Retention purge.
create index idx_link_clicks_clicked_at on link_clicks (clicked_at);
