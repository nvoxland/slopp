(ns slopp.ui.basepath-test
  "Two properties, both of which show up as a blank screen when they break:
  no base is the identity, and prefix-then-strip round-trips. Plus the
  segment-boundary case, where a bare `starts-with?` would hand one project's
  url to another project's router."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ui.basepath :as basepath]))

(deftest no-base-changes-nothing-which-is-the-case-that-must-never-regress
  ;; Every slopp app served directly runs with no prefix, so this is not an
  ;; edge case — it is the default, and a base of "" has to be the identity
  ;; through both directions or part 2 breaks part 1's own UI.
  (doseq [base [nil "" "/"]]
    (testing (str "base " (pr-str base))
      (is (= "/store" (basepath/prefixed base "/store")))
      (is (= "/" (basepath/prefixed base "/")))
      (is (= "/store" (basepath/strip base "/store")))
      (is (= "/" (basepath/strip base "/"))))))

(deftest a-prefix-round-trips-and-an-unknown-path-is-left-alone
  (testing "adding then removing is the identity — the server builds urls with
            prefixed and the browser takes them apart with strip, so a
            disagreement between the two is a blank screen"
    (doseq [p ["/" "/store" "/store/ns/slopp.ui.hub" "/change/d1..d2"]]
      (is (= p (basepath/strip "/p/slopp2" (basepath/prefixed "/p/slopp2" p))) p)))
  (testing "the project root keeps its trailing slash, because /p/slopp2/ is
            the href the picker emits"
    (is (= "/p/slopp2/" (basepath/prefixed "/p/slopp2" "/")))
    (is (= "/" (basepath/strip "/p/slopp2" "/p/slopp2/")))
    (is (= "/" (basepath/strip "/p/slopp2" "/p/slopp2"))
        "the router must reach the timeline whether or not the slash survived"))
  (testing "a base written with a trailing slash means the same thing —
            it arrives over a header and nobody should have to normalise it"
    (is (= "/p/slopp2/store" (basepath/prefixed "/p/slopp2/" "/store"))))
  (testing "a path OUTSIDE the base is returned untouched rather than mangled:
            it is not ours to rewrite, and a silent mangle is the failure that
            looks like a routing bug three layers away"
    (is (= "/other/thing" (basepath/strip "/p/slopp2" "/other/thing")))
    (is (= "/p/slopp22/x" (basepath/strip "/p/slopp2" "/p/slopp22/x"))
        "a prefix match must be on SEGMENTS — slopp22 is not slopp2")))

(deftest a-query-string-is-not-part-of-the-path
  ;; Measured before writing this: (route-for "/store/ns/demo.core?x=1")
  ;; answered {:screen :ns :params {:ns "demo.core?x=1"}} — a namespace named
  ;; after its own query string. Nothing had ever sent one, which is why it
  ;; survived; search is the first screen that must.
  (testing "a path with no query string is unchanged, and its params are empty"
    (is (= ["/store/ns/demo.core" {}] (basepath/split-query "/store/ns/demo.core")))
    (is (= ["/" {}] (basepath/split-query "/"))))
  (testing "the query string comes OFF the path and arrives as data"
    (is (= ["/store/search" {:q "rate"}] (basepath/split-query "/store/search?q=rate"))))
  (testing "several parameters, and one carrying no value at all — a flag is
            the empty string rather than nil, so a caller never has to tell
            absent from blank in two different ways"
    (is (= ["/store/search" {:q "rate" :limit "20" :all ""}]
           (basepath/split-query "/store/search?q=rate&limit=20&all"))))
  (testing "a space survives BOTH encodings: a GET form sends +, a hand-written
            url sends %20"
    (is (= {:q "kg zone"} (second (basepath/split-query "/store/search?q=kg+zone"))))
    (is (= {:q "kg zone"} (second (basepath/split-query "/store/search?q=kg%20zone")))))
  (testing "the punctuation a CODE search is made of — this is the main case
            rather than a corner: a form GET escapes every one of them, and a
            store search that cannot find swap! or ->> has missed the point"
    (is (= {:q "swap!"} (second (basepath/split-query "/store/search?q=swap%21"))))
    (is (= {:q "->>"}   (second (basepath/split-query "/store/search?q=-%3E%3E"))))
    (is (= {:q "a/b"}   (second (basepath/split-query "/store/search?q=a%2Fb")))))
  (testing "a multi-byte character survives, because docstrings carry PROSE —
            a query echoed back as caf%C3%A9 is a screen that looks broken,
            which is worse than a result that is merely absent"
    (is (= {:q "café"} (second (basepath/split-query "/store/search?q=caf%C3%A9"))))
    (is (= {:q "…"}    (second (basepath/split-query "/store/search?q=%E2%80%A6")))))
  (testing "and a codepoint above the basic plane, which needs a surrogate pair"
    ;; Watched red, because `done` asked: removing the decode from
    ;; `split-query` reddened this and ten other assertions, and the drive test
    ;; in `app-test` came with it — showing the heading as `search: kg+zone`
    ;; and `search: a%26b`, which is exactly what a reader would have seen.
    ;;
    ;; the decoding moved to `slopp.lang/decode-component` (jar d24792, D3.1)
    ;; and arrived already able to do this. The implementation it replaced was
    ;; written HERE and its own docstring declined the case in as many words —
    ;; four-byte sequences left literal, because `char` cannot express a
    ;; codepoint above 0xFFFF on both platforms. So this pins a capability the
    ;; local version documented NOT having; the delta is in the store's history
    ;; rather than in a red I can point at.
    (is (= {:q "🙂"} (second (basepath/split-query "/store/search?q=%F0%9F%99%82")))))
  (testing "a malformed escape comes back as ITSELF rather than throwing —
            ?q=100% is a plausible thing to type and an exception in the
            router is a white page"
    (is (= {:q "100%"} (second (basepath/split-query "/store/search?q=100%"))))
    (is (= {:q "%zz"}  (second (basepath/split-query "/store/search?q=%zz"))))))
