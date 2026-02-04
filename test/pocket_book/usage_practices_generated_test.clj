(ns
 pocket-book.usage-practices-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def v2_l14 (def test-dir "/tmp/pocket-dev-practices"))


(def v3_l15 (pocket/set-base-cache-dir! test-dir))


(def v5_l36 (defn example-fn [x] (* x x)))


(def
 v6_l38
 (try (pocket/cached example-fn 5) (catch Exception e (ex-message e))))


(deftest
 t7_l43
 (is
  ((fn* [p1__40644#] (re-find #"requires a var" p1__40644#)) v6_l38)))


(def v9_l55 (pocket/cleanup!))


(def v10_l57 (defn transform [x] (* x 2)))


(def v12_l60 (deref (pocket/cached #'transform 10)))


(deftest t13_l62 (is (= v12_l60 20)))


(def v15_l65 (pocket/invalidate! #'transform 10))


(def v17_l69 (deref (pocket/cached #'transform 1)))


(def v18_l70 (deref (pocket/cached #'transform 2)))


(def v19_l72 (pocket/invalidate-fn! #'transform))


(def v21_l79 (pocket/cleanup!))


(def
 v22_l81
 (defn
  process-data
  [{:keys [data version]}]
  {:result (reduce + data), :version version}))


(def
 v24_l86
 (deref (pocket/cached #'process-data {:data [1 2 3], :version 1})))


(deftest t25_l88 (is (= v24_l86 {:result 6, :version 1})))


(def
 v27_l91
 (deref (pocket/cached #'process-data {:data [1 2 3], :version 2})))


(deftest t28_l93 (is (= v27_l91 {:result 6, :version 2})))


(def v30_l101 (pocket/cleanup!))


(def v32_l133 (pocket/cleanup!))


(def v33_l135 (def call-count (atom 0)))


(def v34_l137 (defn tracked-fn [x] (swap! call-count inc) (* x x)))


(def v36_l142 (reset! call-count 0))


(def
 v37_l144
 (let
  [result (deref (pocket/cached #'tracked-fn 5)) calls @call-count]
  {:result result, :calls calls}))


(deftest t38_l148 (is (= v37_l144 {:result 25, :calls 1})))


(def
 v40_l151
 (let
  [result (deref (pocket/cached #'tracked-fn 5)) calls @call-count]
  {:result result, :calls calls}))


(deftest t41_l155 (is (= v40_l151 {:result 25, :calls 1})))


(def v43_l163 (pocket/cleanup!))


(def v44_l164 (deref (pocket/cached #'transform 1)))


(def v45_l165 (deref (pocket/cached #'transform 2)))


(def v46_l166 (deref (pocket/cached #'tracked-fn 3)))


(def v48_l169 (count (pocket/cache-entries)))


(deftest t49_l171 (is (= v48_l169 3)))


(def v51_l174 (:total-entries (pocket/cache-stats)))


(deftest t52_l176 (is (= v51_l174 3)))


(def v54_l179 (kind/code (pocket/dir-tree)))


(def
 v56_l184
 (->
  (pocket/cache-entries)
  first
  :path
  (str "/meta.edn")
  slurp
  clojure.edn/read-string))


(def v58_l193 (pocket/cache-entries))


(def v60_l197 (pocket/cache-entries (str (ns-name *ns*) "/transform")))


(def v62_l203 (pocket/cleanup!))


(def v63_l205 (def pending-value (pocket/cached #'transform 99)))


(def v64_l207 (pr-str pending-value))


(deftest
 t66_l209
 (is ((fn* [p1__40645#] (re-find #":pending" p1__40645#)) v64_l207)))


(def v67_l211 (deref pending-value))


(def v68_l213 (pr-str pending-value))


(deftest
 t70_l215
 (is ((fn* [p1__40646#] (re-find #":cached" p1__40646#)) v68_l213)))


(def v72_l235 (pocket/cleanup!))


(def
 v73_l237
 (defn
  process-long-text
  [text]
  (str "Processed: " (count text) " chars")))


(def v74_l240 (def long-text (apply str (repeat 300 "x"))))


(def v75_l242 (deref (pocket/cached #'process-long-text long-text)))


(deftest
 t76_l244
 (is
  ((fn [result] (clojure.string/starts-with? result "Processed:"))
   v75_l242)))


(def v78_l248 (kind/code (pocket/dir-tree)))


(def
 v80_l253
 (->
  (pocket/cache-entries (str (ns-name *ns*) "/process-long-text"))
  first
  :fn-name))


(deftest
 t81_l257
 (is
  ((fn
    [fn-name]
    (and
     fn-name
     (clojure.string/ends-with? fn-name "/process-long-text")))
   v80_l253)))


(def v83_l281 (pocket/cleanup!))


(def v84_l283 (defn generate-data [n] (doall (range n))))


(def v86_l286 (deref (pocket/cached #'generate-data 5)))


(deftest t87_l288 (is (= v86_l286 [0 1 2 3 4])))


(def v89_l355 (pocket/cleanup!))
