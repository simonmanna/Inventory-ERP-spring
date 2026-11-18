"""Docker ``HEALTHCHECK``: exit 0 when the local health endpoint answers 200, else 1."""

from __future__ import annotations

import os
import sys
import urllib.error
import urllib.request


def main() -> int:
    port = int(os.environ.get("LP_EDGE_HEALTH_PORT", "8765"))
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=4) as response:  # noqa: S310
            return 0 if response.status == 200 else 1
    except (urllib.error.URLError, OSError, ValueError):
        return 1


if __name__ == "__main__":
    sys.exit(main())
