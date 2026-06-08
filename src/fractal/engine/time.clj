(ns fractal.engine.time
  "L0 · IO-free time helpers. `now-str` is the one impure fn (reads the wall
   clock); everything else here must stay pure so the rest of the engine can
   inject a clock where reproducibility matters (10 §4)."
  (:import [java.time Instant]))

(defn now-str
  "Current instant as an ISO-8601 / RFC-3339 string (UTC, e.g.
   \"2026-06-07T12:34:56.789Z\"). Stamped onto every event's :event/at and
   every entity's …/started-at / …/ended-at (02)."
  []
  (str (Instant/now)))
