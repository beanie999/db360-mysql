-- 1,000 users (user1..user1000, password Passw0rd-<n>) and 100,000 historical login attempts.
TRUNCATE login_audit, users RESTART IDENTITY;

INSERT INTO users (username, email, password_hash, last_login)
SELECT 'user' || n,
       'user' || n || '@example.com',
       crypt('Passw0rd-' || n, gen_salt('bf', 6)),
       now() - random() * interval '30 days'
FROM generate_series(1, 1000) AS n;

INSERT INTO login_audit (username, success, reason, source_ip, created_at)
SELECT 'user' || (1 + floor(random() * 1000))::int,
       ok,
       CASE WHEN ok THEN NULL
            ELSE (ARRAY['bad_password', 'bad_password', 'unknown_user', 'account_locked'])[1 + floor(random() * 4)::int] END,
       '10.0.' || floor(random() * 255)::int || '.' || floor(random() * 255)::int,
       now() - random() * interval '30 days'
FROM (SELECT random() < 0.85 AS ok FROM generate_series(1, 100000)) AS s;

ANALYZE;
SELECT 'users' AS tbl, count(*) FROM users UNION ALL SELECT 'login_audit', count(*) FROM login_audit;
