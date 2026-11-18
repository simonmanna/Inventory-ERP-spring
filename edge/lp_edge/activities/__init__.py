"""Activity recognition research (LP7, shadow mode): recognizers over a zone's object tracks, sent as
``vision.activity``. No backend rule reads an activity; reviews and labelled footage measure the recognizers.

See :mod:`lp_edge.activities.base` for what a recognizer is and the limits it keeps (objects only: no pose, no
faces, no identity), :mod:`lp_edge.activities.loader` for how a zone picks them, and
:mod:`lp_edge.activities.research` for how they run beside a zone's profile.
"""

from lp_edge.activities.base import BaseRecognizer, Recognizer, Segment
from lp_edge.activities.loader import BASELINE, ENTRY_POINT_GROUP, installed, recognizers_for

__all__ = ["BASELINE", "ENTRY_POINT_GROUP", "BaseRecognizer", "Recognizer", "Segment", "installed",
           "recognizers_for"]
