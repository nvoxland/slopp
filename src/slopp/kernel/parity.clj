(ns slopp.kernel.parity
  "Keeping the KERNEL's two copies honest.

  `slopp.kernel.rt` and `slopp.kernel.boot` are the one part of slopp that is not only a
  store namespace: each also exists as a hand-maintained file on `main`, and
  both copies are live. That makes the kernel the single layer a store-wide
  sweep cannot see — a rename that rewrites every reference in the store walks
  straight past a file — and it has drifted three times.

  Everything here is PURE and takes source STRINGS, which is the load-bearing
  constraint rather than a style preference: in every context a test runs, the
  \"file\" IS the store's own rendering, so a test that reads it compares the
  store to itself and passes vacuously. Only a caller standing where both
  copies are real can supply them, so the comparison must be something such a
  caller can invoke — a CI lane, or the MCP server in the repo root."
  (:require [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]
            [clojure.walk :as walk]
            [clojure.string :as str]))
