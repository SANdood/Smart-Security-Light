Smart-Security-Light
====================

Adapted from Smart NightLIght - on when motion detected after dark, off at dawn or after motion stops - unless manually overridden at the switch.

Manual overrides:

* Manual ON while light is off - turns on the light and disables motion-control until the light is turned off (manually or by another application - e.g. Goodnight! action). Also resets when it gets light again.
* Double-tap ON while light is on due to motion - stops motion control and timed off, keeps light on. Motion-control re-enabled as with Manual ON
* Double-tap OFF - stops motion control and keeps the light off until re-enabled as above
* The double-tap overrides can optionally provide positive feedback by flashing the light

Supports multiple motion sensors, but only a single light switch (for now).
