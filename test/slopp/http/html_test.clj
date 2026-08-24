(ns slopp.http.html-test
  "SECURITY tests: these pin the rendering contract of slopp.http.html — the
  wrapper's refusals AND the hiccup dep's escaping behavior. A red here on a
  hiccup upgrade means the escaping contract changed underneath us; treat it
  as a security event, not a formatting nit."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.http.html :as html] [clojure.string :as str]))

(deftest text-escaping-blocks-injection
  (testing "SECURITY: text children are escaped by default"
    (is (= "<p>&lt;script&gt;alert(1)&lt;/script&gt; &amp; it&apos;s</p>"
           (html/render [:p "<script>alert(1)</script> & it's"])))))

(deftest attribute-escaping-blocks-breakout
  (testing "SECURITY: attribute values cannot close their own quoting"
    (is (= "<div title=\"&quot; onmouseover=&quot;x()\"></div>"
           (html/render [:div {:title "\" onmouseover=\"x()"}])))
    (is (= "<div title=\"it&apos;s\"></div>"
           (html/render [:div {:title "it's"}])))))

(deftest attr-and-tag-names-are-validated
  (testing "SECURITY: hiccup renders crafted tag/attr NAMES verbatim — the wrapper refuses them"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid tag"
                          (html/render [(keyword "div onload=x") "hi"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid attribute name"
                          (html/render [:div {(keyword "onload=x") "y"}])))))

(deftest url-attrs-refuse-script-schemes
  (testing "SECURITY: escaping cannot neutralize a javascript:/data: URL — refuse at render"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refused URL scheme"
                          (html/render [:a {:href "javascript:alert(1)"} "x"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refused URL scheme"
                          (html/render [:a {:href " jAvaScript:alert(1)"} "x"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refused URL scheme"
                          (html/render [:img {:src "data:text/html,<script>"}])))
    (is (= "<a href=\"/store\">x</a>" (html/render [:a {:href "/store"} "x"])))
    (is (= "<a href=\"https://example.com\">x</a>"
           (html/render [:a {:href "https://example.com"} "x"])))))

(deftest raw-island-is-verbatim-and-string-only
  (testing "SECURITY: [:html/raw s] is the ONE escaping bypass; string payload only"
    (is (= "<div><b>x</b></div>" (html/render [:div [:html/raw "<b>x</b>"]])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ONE string payload"
                          (html/render [:div [:html/raw {:a 1}]])))))

(deftest rendering-contract-of-the-dep
  (testing "id/class sugar"
    (is (= "<div class=\"b c\" id=\"a\">t</div>" (html/render [:div#a.b.c "t"]))))
  (testing "boolean attrs: true renders bare, false/nil omit"
    (is (= "<input disabled>"
           (html/render [:input {:disabled true :checked false :value nil}]))))
  (testing "void tags render without a closer"
    (is (= "<br>" (html/render [:br]))))
  (testing "nil children vanish, numbers render, seqs splice"
    (is (= "<div>x5</div>" (html/render [:div nil "x" 5])))
    (is (= "<ul><li>1</li><li>2</li></ul>"
           (html/render [:ul (for [i [1 2]] [:li i])]))))
  (testing "style maps serialize"
    (is (= "<div style=\"color:red;margin:0;\">s</div>"
           (html/render [:div {:style {:color "red" :margin "0"}} "s"])))))

(deftest teaching-errors-name-the-fix
  (testing "a map in child position teaches the cond-> idiom"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cond->"
                          (html/render [:div "text" {:class "x"}]))))
  (testing "a vector used to group siblings teaches seqs"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"group siblings with a seq"
                          (html/render [:div [["a"] ["b"]]])))))

(deftest html-response-is-a-raw-html-ring-map
  (is (= {:status 200
          :http/raw true
          :http/hiccup [:p "hi"]
          :headers {"Content-Type" "text/html; charset=utf-8"}
          :body "<p>hi</p>"}
         (html/html-response [:p "hi"])))

  (testing "the hiccup travels BESIDE the body, not instead of it"
    ;; an adapter writes :body and ignores the rest; a reader that wants the
    ;; PAGE rather than the bytes reads :http/hiccup. Both, because they have
    ;; different consumers — dropping either would serve one of them nothing.
    ;;
    ;; It is set here because this is the only place holding both. Downstream
    ;; the hiccup is gone, and recovering it would mean parsing HTML to get
    ;; back something that existed one function earlier. slopp-ui measured the
    ;; cost of not doing it: every page served through this helper drove as
    ;; escaped markup in a <pre>, with no regions and nothing clickable.
    (let [r (html/html-response [:main [:h1 "t"]])]
      (is (= [:main [:h1 "t"]] (:http/hiccup r)))
      (is (= "<main><h1>t</h1></main>" (:body r)))))

  (testing "opts merge status and headers; Content-Type stays ours"
    (let [r (html/html-response [:p "x"] {:status 404
                                          :headers {"X-A" "1"
                                                    "Content-Type" "nope"}})]
      (is (= 404 (:status r)))
      (is (= "1" (get-in r [:headers "X-A"])))
      (is (= "text/html; charset=utf-8" (get-in r [:headers "Content-Type"]))))))

(deftest a-whole-DOCUMENT-gets-its-doctype-from-the-renderer
  (testing "a top-level [:html …] is a document, so the doctype is prepended"
    (is (= (str "<!DOCTYPE html><html lang=\"en\">"
                "<head><meta charset=\"utf-8\"><title>T &amp; t&apos;s</title>"
                "<link href=\"/assets/app.css\" rel=\"stylesheet\">"
                "</head>"
                "<body><main>b</main></body></html>")
           (html/render
            [:html {:lang "en"}
             [:head [:meta {:charset "utf-8"}] [:title "T & t's"]
              [:link {:rel "stylesheet" :href "/assets/app.css"}]]
             [:body [:main "b"]]]))
        "title, lang and head elements are written where they go — an app that
         holds the whole document needs no parameter for any of them"))
  (testing "a FRAGMENT gets no doctype, which is what makes the rule safe"
    (is (= "<main>b</main>" (html/render [:main "b"])))
    (is (= "<div><html-ish></html-ish></div>"
           (html/render [:div [:html-ish]]))
        "only the top-level tag decides, and only when it is exactly :html")))

(deftest a-shell-the-framework-cannot-complete-REFUSES
  (testing "no head: there is nowhere to put the bundle"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"no \[:head"
         (html/complete-shell [:html [:body [:div {:id "app"}]]] "/js/main.js" ""))))
  (testing "no mount point: the app has nothing to render into"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"no mount point"
         (html/complete-shell [:html [:head] [:body [:div {:id "root"}]]]
                              "/js/main.js" ""))
        "an author who wrote #root gets a message instead of a blank screen"))
  (testing "not hiccup at all"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"must be a hiccup DOCUMENT"
         (html/complete-shell "<html></html>" "/js/main.js" ""))))
  (testing "the #app SHORTHAND counts, because hiccup authors write it"
    (let [done (html/complete-shell [:html [:head] [:body [:div#app.wrap]]]
                                    "/js/main.js" "/p/x")]
      (is (str/includes? (html/render done) "data-base=\"/p/x\"")
          (html/render done))))
  (testing "an absent base is the ROOT, and says so rather than being omitted"
    (is (str/includes?
         (html/render (html/complete-shell [:html [:head] [:body [:div#app]]]
                                           "/js/main.js" nil))
         "data-base=\"\"")
        "the attribute is always present, so a missing one is a missing SHELL
         rather than a deployment at the root")))
