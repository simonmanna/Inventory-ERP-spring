"""``python -m lp_edge``: the same command line as ``lp-edge``."""

import sys

from lp_edge.cli import main

if __name__ == "__main__":
    sys.exit(main())
