(ns
 pocket-book.usage-practices-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def v2_l22 (def test-dir "/tmp/pocket-dev-practices"))


(def v3_l23 (pocket/set-base-cache-dir! test-dir))


(def v5_l73 (defn example-fn [x] (* x x)))


(def
 v6_l75
 (try (pocket/cached example-fn 5) (catch Exception e (ex-message e))))


(deftest
 t7_l80
 (is
  ((fn* [p1__70859#] (re-find #"requires a var or keyword" p1__70859#))
   v6_l75)))


(def v9_l92 (pocket/cleanup!))


(def v10_l94 (defn transform [x] (* x 2)))


(def v12_l97 (deref (pocket/cached #'transform 10)))


(deftest t13_l99 (is (= v12_l97 20)))


(def v15_l102 (pocket/invalidate! #'transform 10))


(def v17_l106 (deref (pocket/cached #'transform 1)))


(def v18_l107 (deref (pocket/cached #'transform 2)))


(def v19_l109 (pocket/invalidate-fn! #'transform))


(def v21_l116 (pocket/cleanup!))


(def
 v22_l118
 (defn
  process-data
  [{:keys [data version]}]
  {:result (reduce + data), :version version}))


(def
 v24_l123
 (deref (pocket/cached #'process-data {:data [1 2 3], :version 1})))


(deftest t25_l125 (is (= v24_l123 {:result 6, :version 1})))


(def
 v27_l128
 (deref (pocket/cached #'process-data {:data [1 2 3], :version 2})))


(deftest t28_l130 (is (= v27_l128 {:result 6, :version 2})))


(def v30_l138 (pocket/cleanup!))


(def v32_l170 (pocket/cleanup!))


(def v33_l172 (def call-count (atom 0)))


(def v34_l174 (defn tracked-fn [x] (swap! call-count inc) (* x x)))


(def v36_l179 (reset! call-count 0))


(def
 v37_l181
 (let
  [result (deref (pocket/cached #'tracked-fn 5)) calls @call-count]
  {:result result, :calls calls}))


(deftest t38_l185 (is (= v37_l181 {:result 25, :calls 1})))


(def
 v40_l188
 (let
  [result (deref (pocket/cached #'tracked-fn 5)) calls @call-count]
  {:result result, :calls calls}))


(deftest t41_l192 (is (= v40_l188 {:result 25, :calls 1})))


(def v43_l200 (pocket/cleanup!))


(def v44_l201 (deref (pocket/cached #'transform 1)))


(def v45_l202 (deref (pocket/cached #'transform 2)))


(def v46_l203 (deref (pocket/cached #'tracked-fn 3)))


(def v48_l206 (count (pocket/cache-entries)))


(deftest t49_l208 (is (= v48_l206 3)))


(def v51_l211 (:total-entries (pocket/cache-stats)))


(deftest t52_l213 (is (= v51_l211 3)))


(def v54_l216 (pocket/dir-tree))


(def
 v56_l221
 (->
  (pocket/cache-entries)
  first
  :path
  (str "/meta.edn")
  slurp
  clojure.edn/read-string))


(def v58_l230 (pocket/cache-entries))


(def v60_l234 (pocket/cache-entries (str (ns-name *ns*) "/transform")))


(def v62_l240 (pocket/cleanup!))


(def v63_l242 (def pending-value (pocket/cached #'transform 99)))


(def v65_l245 (pr-str pending-value))


(deftest
 t66_l247
 (is ((fn* [p1__70860#] (re-find #":pending" p1__70860#)) v65_l245)))


(def v67_l249 (deref pending-value))


(def v69_l252 (pr-str pending-value))


(deftest
 t70_l254
 (is ((fn* [p1__70861#] (re-find #":cached" p1__70861#)) v69_l252)))


(def v72_l274 (pocket/cleanup!))


(def
 v73_l276
 (defn
  process-long-text
  [text]
  (str "Processed: " (count text) " chars")))


(def v74_l279 (def long-text (apply str (repeat 300 "x"))))


(def v75_l281 (deref (pocket/cached #'process-long-text long-text)))


(deftest
 t76_l283
 (is
  ((fn [result] (clojure.string/starts-with? result "Processed:"))
   v75_l281)))


(def v78_l287 (pocket/dir-tree))


(def
 v80_l292
 (->
  (pocket/cache-entries (str (ns-name *ns*) "/process-long-text"))
  first
  :fn-name))


(deftest
 t81_l296
 (is
  ((fn
    [fn-name]
    (and
     fn-name
     (clojure.string/ends-with? fn-name "/process-long-text")))
   v80_l292)))


(def v83_l326 (pocket/cleanup!))


(def v84_l328 (defn generate-data [n] (doall (range n))))


(def v85_l332 (deref (pocket/cached #'generate-data 5)))


(deftest t86_l334 (is (= v85_l332 [0 1 2 3 4])))


(def v88_l367 (pocket/cleanup!))
