"""Loss-prevention edge agent for Inventory-ERP.

The agent runs on the customer's network. It reads the cameras, keeps an encrypted ring buffer of video on its own
disk, turns what it sees into ledger observations (``vision.*`` events) and sends them out over HTTPS. Video leaves
the box only when an investigator asks for a clip. See ``docs/spec/loss-prevention.md`` (LP1 and LP2).
"""

__version__ = "0.1.0"

#: The ledger ``source`` of every observation this agent sends.
EVENT_SOURCE = "edge-agent"
