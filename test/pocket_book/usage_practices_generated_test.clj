(ns
 pocket-book.usage-practices-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def v2_l20 (def test-dir "/tmp/pocket-dev-practices"))


(def v3_l21 (pocket/set-base-cache-dir! test-dir))


(def v5_l71 (defn example-fn [x] (* x x)))


(def
 v6_l73
 (try (pocket/cached example-fn 5) (catch Exception e (ex-message e))))


(deftest
 t7_l78
 (is
  ((fn* [p1__69182#] (re-find #"requires a var or keyword" p1__69182#))
   v6_l73)))


(def v9_l90 (pocket/cleanup!))


(def v10_l92 (defn transform [x] (* x 2)))


(def v12_l95 (deref (pocket/cached #'transform 10)))


(deftest t13_l97 (is (= v12_l95 20)))


(def v15_l100 (pocket/invalidate! #'transform 10))


(def v17_l104 (deref (pocket/cached #'transform 1)))


(def v18_l105 (deref (pocket/cached #'transform 2)))


(def v19_l107 (pocket/invalidate-fn! #'transform))


(def v21_l114 (pocket/cleanup!))


(def
 v22_l116
 (defn
  process-data
  [{:keys [data version]}]
  {:result (reduce + data), :version version}))


(def
 v24_l121
 (deref (pocket/cached #'process-data {:data [1 2 3], :version 1})))


(deftest t25_l123 (is (= v24_l121 {:result 6, :version 1})))


(def
 v27_l126
 (deref (pocket/cached #'process-data {:data [1 2 3], :version 2})))


(deftest t28_l128 (is (= v27_l126 {:result 6, :version 2})))


(def v30_l136 (pocket/cleanup!))


(def v32_l168 (pocket/cleanup!))


(def v33_l170 (def call-count (atom 0)))


(def v34_l172 (defn tracked-fn [x] (swap! call-count inc) (* x x)))


(def v36_l177 (reset! call-count 0))


(def
 v37_l179
 (let
  [result (deref (pocket/cached #'tracked-fn 5)) calls @call-count]
  {:result result, :calls calls}))


(deftest t38_l183 (is (= v37_l179 {:result 25, :calls 1})))


(def
 v40_l186
 (let
  [result (deref (pocket/cached #'tracked-fn 5)) calls @call-count]
  {:result result, :calls calls}))


(deftest t41_l190 (is (= v40_l186 {:result 25, :calls 1})))


(def v43_l198 (pocket/cleanup!))


(def v44_l199 (deref (pocket/cached #'transform 1)))


(def v45_l200 (deref (pocket/cached #'transform 2)))


(def v46_l201 (deref (pocket/cached #'tracked-fn 3)))


(def v48_l204 (count (pocket/cache-entries)))


(deftest t49_l206 (is (= v48_l204 3)))


(def v51_l209 (:total-entries (pocket/cache-stats)))


(deftest t52_l211 (is (= v51_l209 3)))


(def v54_l214 (pocket/dir-tree))


(def
 v56_l219
 (->
  (pocket/cache-entries)
  first
  :path
  (str "/meta.edn")
  slurp
  clojure.edn/read-string))


(def v58_l228 (pocket/cache-entries))


(def v60_l232 (pocket/cache-entries (str (ns-name *ns*) "/transform")))


(def v62_l238 (pocket/cleanup!))


(def v63_l240 (def pending-value (pocket/cached #'transform 99)))


(def v65_l243 (pr-str pending-value))


(deftest
 t66_l245
 (is ((fn* [p1__69183#] (re-find #":pending" p1__69183#)) v65_l243)))


(def v67_l247 (deref pending-value))


(def v69_l250 (pr-str pending-value))


(deftest
 t70_l252
 (is ((fn* [p1__69184#] (re-find #":cached" p1__69184#)) v69_l250)))


(def v72_l272 (pocket/cleanup!))


(def
 v73_l274
 (defn
  process-long-text
  [text]
  (str "Processed: " (count text) " chars")))


(def v74_l277 (def long-text (apply str (repeat 300 "x"))))


(def v75_l279 (deref (pocket/cached #'process-long-text long-text)))


(deftest
 t76_l281
 (is
  ((fn [result] (clojure.string/starts-with? result "Processed:"))
   v75_l279)))


(def v78_l285 (pocket/dir-tree))


(def
 v80_l290
 (->
  (pocket/cache-entries (str (ns-name *ns*) "/process-long-text"))
  first
  :fn-name))


(deftest
 t81_l294
 (is
  ((fn
    [fn-name]
    (and
     fn-name
     (clojure.string/ends-with? fn-name "/process-long-text")))
   v80_l290)))


(def v83_l324 (pocket/cleanup!))


(def v84_l326 (defn generate-data [n] (doall (range n))))


(def v85_l330 (deref (pocket/cached #'generate-data 5)))


(deftest t86_l332 (is (= v85_l330 [0 1 2 3 4])))


(def v88_l365 (pocket/cleanup!))
