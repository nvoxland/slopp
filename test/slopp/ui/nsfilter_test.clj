(ns slopp.ui.nsfilter-test
  "Tests for the Code section's namespace filter — the one piece of the
  left-pane search that is neither a DOM event nor a render.

  It exists as its own `:cljc` namespace, and therefore as its own test
  namespace, for the reason the whole client split exists: matching is the
  part that can be WRONG, and the part a browser is the worst place to find
  out about. What is left in `slopp.ui.client.app` is reading the input's
  value and re-rendering, which cannot be meaningfully asserted anywhere."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ui.nsfilter :as nsfilter]))

(deftest matches?-is-a-trimmed-case-insensitive-substring
  (testing "an empty or blank needle matches everything (nothing typed = show all)"
    (is (nsfilter/matches? "" "slopp.http.browse"))
    (is (nsfilter/matches? "   " "slopp.http.browse")))
  (testing "case-insensitive substring match"
    (is (nsfilter/matches? "HTTP" "slopp.http.browse"))
    (is (nsfilter/matches? "browse" "slopp.http.browse"))
    (is (not (nsfilter/matches? "zzz" "slopp.http.browse"))))
  (testing "the needle is trimmed before matching"
    (is (nsfilter/matches? "  http  " "slopp.http.browse"))
    (is (not (nsfilter/matches? "  zzz  " "slopp.http.browse")))))

(deftest a-common-root-is-the-prefix-that-still-leaves-every-name-something
  (testing "the shared dotted prefix, longest that leaves a remainder"
    (is (= "demo" (nsfilter/common-root ["demo.a" "demo.b"])))
    (is (= "demo.web" (nsfilter/common-root ["demo.web.a" "demo.web.b"]))))
  (testing "nil when hoisting a root would buy nothing"
    (is (nil? (nsfilter/common-root ["foo.a" "bar.b"])) "nothing shared")
    (is (nil? (nsfilter/common-root ["only.one"])) "one name is not a category")
    (is (nil? (nsfilter/common-root []))))
  (testing "a name that IS the prefix stops it — the remainder would be empty"
    (is (nil? (nsfilter/common-root ["demo" "demo.a"]))))
  (testing "stripping is by whole segments, and leaves anything else alone"
    (is (= "a.core" (nsfilter/without-root "demo" "demo.a.core")))
    (is (= "demo.a" (nsfilter/without-root nil "demo.a")))
    (is (= "other.x" (nsfilter/without-root "demo" "other.x")))
    (is (= "demonstrate.x" (nsfilter/without-root "demo" "demonstrate.x"))
        "demo. is the boundary, not demo")))
