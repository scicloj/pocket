(ns
 pocket-book.usage-practices-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def v2_l13 (def test-dir "/tmp/pocket-dev-practices"))


(def v3_l14 (pocket/set-base-cache-dir! test-dir))


(def v5_l35 (defn example-fn [x] (* x x)))


(def
 v6_l37
 (try (pocket/cached example-fn 5) (catch Exception e (ex-message e))))


(deftest
 t7_l42
 (is
  ((fn* [p1__38181#] (re-find #"requires a var" p1__38181#)) v6_l37)))


(def v9_l54 (pocket/cleanup!))


(def v10_l56 (defn transform [x] (* x 2)))


(def v12_l59 (deref (pocket/cached #'transform 10)))


(deftest t13_l61 (is (= v12_l59 20)))


(def v15_l64 (pocket/invalidate! #'transform 10))


(def v17_l68 (deref (pocket/cached #'transform 1)))


(def v18_l69 (deref (pocket/cached #'transform 2)))


(def v19_l71 (pocket/invalidate-fn! #'transform))


(def v21_l78 (pocket/cleanup!))


(def
 v22_l80
 (defn
  process-data
  [{:keys [data version]}]
  {:result (reduce + data), :version version}))


(def
 v24_l85
 (deref (pocket/cached #'process-data {:data [1 2 3], :version 1})))


(deftest t25_l87 (is (= v24_l85 {:result 6, :version 1})))


(def
 v27_l90
 (deref (pocket/cached #'process-data {:data [1 2 3], :version 2})))


(deftest t28_l92 (is (= v27_l90 {:result 6, :version 2})))


(def v30_l100 (pocket/cleanup!))


(def v32_l132 (pocket/cleanup!))


(def v33_l134 (def call-count (atom 0)))


(def v34_l136 (defn tracked-fn [x] (swap! call-count inc) (* x x)))


(def v36_l141 (reset! call-count 0))


(def
 v37_l143
 (let
  [result (deref (pocket/cached #'tracked-fn 5)) calls @call-count]
  {:result result, :calls calls}))


(deftest t38_l147 (is (= v37_l143 {:result 25, :calls 1})))


(def
 v40_l150
 (let
  [result (deref (pocket/cached #'tracked-fn 5)) calls @call-count]
  {:result result, :calls calls}))


(deftest t41_l154 (is (= v40_l150 {:result 25, :calls 1})))


(def v43_l162 (pocket/cleanup!))


(def v44_l163 (deref (pocket/cached #'transform 1)))


(def v45_l164 (deref (pocket/cached #'transform 2)))


(def v46_l165 (deref (pocket/cached #'tracked-fn 3)))


(def v48_l168 (count (pocket/cache-entries)))


(deftest t49_l170 (is (= v48_l168 3)))


(def v51_l173 (:total-entries (pocket/cache-stats)))


(deftest t52_l175 (is (= v51_l173 3)))


(def v54_l178 (kind/code (pocket/dir-tree)))


(def
 v56_l183
 (->
  (pocket/cache-entries)
  first
  :path
  (str "/meta.edn")
  slurp
  clojure.edn/read-string))


(def v58_l192 (pocket/cache-entries))


(def v60_l196 (pocket/cache-entries (str (ns-name *ns*) "/transform")))


(def v62_l202 (pocket/cleanup!))


(def v63_l204 (def pending-value (pocket/cached #'transform 99)))


(def v64_l206 (pr-str pending-value))


(deftest
 t66_l208
 (is ((fn* [p1__38182#] (re-find #":pending" p1__38182#)) v64_l206)))


(def v67_l210 (deref pending-value))


(def v68_l212 (pr-str pending-value))


(deftest
 t70_l214
 (is ((fn* [p1__38183#] (re-find #":cached" p1__38183#)) v68_l212)))


(def v72_l234 (pocket/cleanup!))


(def
 v73_l236
 (defn
  process-long-text
  [text]
  (str "Processed: " (count text) " chars")))


(def v74_l239 (def long-text (apply str (repeat 300 "x"))))


(def v75_l241 (deref (pocket/cached #'process-long-text long-text)))


(deftest
 t76_l243
 (is
  ((fn [result] (clojure.string/starts-with? result "Processed:"))
   v75_l241)))


(def v78_l247 (kind/code (pocket/dir-tree)))


(def
 v80_l252
 (->
  (pocket/cache-entries (str (ns-name *ns*) "/process-long-text"))
  first
  :fn-name))


(deftest
 t81_l256
 (is
  ((fn
    [fn-name]
    (and
     fn-name
     (clojure.string/ends-with? fn-name "/process-long-text")))
   v80_l252)))


(def v83_l280 (pocket/cleanup!))


(def v84_l282 (defn generate-data [n] (doall (range n))))


(def v86_l285 (deref (pocket/cached #'generate-data 5)))


(deftest t87_l287 (is (= v86_l285 [0 1 2 3 4])))


(def v89_l354 (pocket/cleanup!))
