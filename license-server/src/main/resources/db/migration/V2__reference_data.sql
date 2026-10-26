-- V2: the catalogue the business sells. Edit through /admin/plans; this is only the starting set.

INSERT INTO product (code, name, description) VALUES
    ('erp', 'Inventory ERP', 'Cafe POS + full ERP (web, API and Android terminals)')
ON CONFLICT (code) DO NOTHING;

-- features: the keys the ERP understands (web/src/lib/features.ts + @RequiresModule names).
INSERT INTO plan (id, code, name, product_code, period, period_count, price_minor, currency,
                  max_devices, max_users, max_organizations, instance_limit, features, grace_days,
                  is_trial, is_active, sort_order)
VALUES
    ('plan-trial-14d', 'trial-14d', 'Trial (14 days)', 'erp', 'TRIAL', 14, 0, 'USD',
     2, 5, 1, 1,
     '["crm","accounting","expense","taskBoard","androidPos","advancedReports"]'::jsonb,
     0, true, true, 0),

    ('plan-starter-day', 'starter-daily', 'Starter (daily)', 'erp', 'DAY', 1, 300, 'USD',
     1, 3, 1, 1,
     '["accounting","expense","androidPos"]'::jsonb,
     1, false, true, 10),

    ('plan-starter-month', 'starter-monthly', 'Starter (monthly)', 'erp', 'MONTH', 1, 4900, 'USD',
     2, 5, 1, 1,
     '["accounting","expense","androidPos"]'::jsonb,
     7, false, true, 20),

    ('plan-pro-month', 'pro-monthly', 'Pro (monthly)', 'erp', 'MONTH', 1, 12900, 'USD',
     5, 25, 1, 1,
     '["accounting","expense","crm","taskBoard","assets","androidPos","webhooks","advancedReports"]'::jsonb,
     7, false, true, 30),

    ('plan-pro-year', 'pro-yearly', 'Pro (yearly)', 'erp', 'YEAR', 1, 129000, 'USD',
     5, 25, 1, 1,
     '["accounting","expense","crm","taskBoard","assets","androidPos","webhooks","advancedReports"]'::jsonb,
     14, false, true, 40),

    ('plan-enterprise-year', 'enterprise-yearly', 'Enterprise (yearly)', 'erp', 'YEAR', 1, 399000, 'USD',
     50, 250, 10, 5,
     '["accounting","expense","crm","taskBoard","assets","rental","manufacturing","repair","hr","beverage","communication","multiOrg","androidPos","apiAccess","webhooks","advancedReports","backupOffsite"]'::jsonb,
     14, false, true, 50),

    ('plan-perpetual', 'perpetual-onprem', 'Perpetual (on-premise)', 'erp', 'PERPETUAL', 1, 890000, 'USD',
     10, 50, 3, 1,
     '["accounting","expense","crm","taskBoard","assets","rental","manufacturing","repair","hr","beverage","androidPos","webhooks","advancedReports"]'::jsonb,
     0, false, true, 60)
ON CONFLICT (code) DO NOTHING;
