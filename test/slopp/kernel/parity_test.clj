(ns slopp.kernel.parity-test
  "Cover for the kernel parity comparator.

  Same-package by necessity — the subject is package-private to
  `slopp.store.*` — and in-image by nature, since the comparison is pure.

  Every fixture here is a shape that actually happened: the two historical
  drifts (a whole public form missing, `:isolated` where the other said
  `:external`), the two false positives the comparator produced on its first
  real run (reader gensyms, re-wrapped docstrings), and the escape's own
  failure mode. A parity check is easy to tune by taste until it agrees with
  today's diff; grounding each case in a real incident is what keeps it a
  guard rather than a description."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.kernel.parity :as parity]))
