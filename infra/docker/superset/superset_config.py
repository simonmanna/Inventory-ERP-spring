# Apache Superset configuration for the ERP's embedded dashboards (ADR-S078, docs/integrations.md).
#
# Superset is reached two ways: the API calls it on the internal network to mint guest tokens, and the browser loads
# embedded dashboards from SUPERSET_PUBLIC_URL inside an iframe of the ERP web app. Every secret comes from the
# environment; nothing here is specific to one customer.
import os


def _env(name, default=None):
    value = os.environ.get(name, default)
    if value is None or value == "":
        raise RuntimeError(f"{name} must be set for Superset")
    return value


SECRET_KEY = _env("SUPERSET_SECRET_KEY")
SQLALCHEMY_DATABASE_URI = _env("SUPERSET_METADATA_DB_URI")

FEATURE_FLAGS = {
    "EMBEDDED_SUPERSET": True,
    "DASHBOARD_NATIVE_FILTERS": True,
    "ENABLE_TEMPLATE_PROCESSING": False,
}

# Guest tokens (minted by the ERP API for one dashboard, carrying the organization's row-level security clause).
# Lifetime matches SupersetEmbedService.GUEST_TOKEN_SECONDS; the embedded SDK asks for a new one before it expires.
GUEST_ROLE_NAME = "EmbedViewer"
GUEST_TOKEN_JWT_SECRET = _env("SUPERSET_GUEST_TOKEN_SECRET")
GUEST_TOKEN_JWT_EXP_SECONDS = 300

# Who may frame the embedded dashboards: the ERP web origins, space separated (e.g. "https://erp.example.com").
_erp_origins = os.environ.get("SUPERSET_FRAME_ANCESTORS", "'self'").split()

TALISMAN_ENABLED = True
TALISMAN_CONFIG = {
    "content_security_policy": {
        "base-uri": ["'self'"],
        "default-src": ["'self'"],
        "img-src": ["'self'", "blob:", "data:"],
        "worker-src": ["'self'", "blob:"],
        "connect-src": ["'self'"],
        "object-src": "'none'",
        "style-src": ["'self'", "'unsafe-inline'"],
        "script-src": ["'self'", "'strict-dynamic'"],
        "frame-ancestors": ["'self'", *_erp_origins],
    },
    "content_security_policy_nonce_in": ["script-src"],
    # The CSP frame-ancestors above decides who may frame Superset; X-Frame-Options would forbid every other origin.
    "frame_options": None,
    "force_https": False,
    "session_cookie_secure": os.environ.get("SUPERSET_COOKIE_SECURE", "false").lower() == "true",
}

# Nobody browses Superset anonymously; only the platform operator logs in to build dashboards.
PUBLIC_ROLE_LIKE = None
AUTH_ROLE_PUBLIC = "Public"

# Results and metadata cache in Redis, so a dashboard does not hit Postgres on every viewer.
_redis = os.environ.get("SUPERSET_REDIS_URL", "redis://superset-redis:6379/0")
CACHE_CONFIG = {
    "CACHE_TYPE": "RedisCache",
    "CACHE_DEFAULT_TIMEOUT": 300,
    "CACHE_KEY_PREFIX": "superset_",
    "CACHE_REDIS_URL": _redis,
}
DATA_CACHE_CONFIG = {**CACHE_CONFIG, "CACHE_KEY_PREFIX": "superset_data_"}
FILTER_STATE_CACHE_CONFIG = {**CACHE_CONFIG, "CACHE_KEY_PREFIX": "superset_filter_"}
EXPLORE_FORM_DATA_CACHE_CONFIG = {**CACHE_CONFIG, "CACHE_KEY_PREFIX": "superset_explore_"}

# Read-only source: no CSV upload into the ERP database, no DML from SQL Lab.
PREVENT_UNSAFE_DB_CONNECTIONS = True
SQLLAB_CTAS_NO_LIMIT = False
ROW_LIMIT = 50000
