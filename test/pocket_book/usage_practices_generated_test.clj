(ns
 pocket-book.usage-practices-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def v2_l16 (def test-dir "/tmp/pocket-dev-practices"))


(def v3_l17 (pocket/set-base-cache-dir! test-dir))


(def v5_l38 (defn example-fn [x] (* x x)))


(def
 v6_l40
 (try (pocket/cached example-fn 5) (catch Exception e (ex-message e))))


(deftest
 t7_l45
 (is
  ((fn* [p1__28113#] (re-find #"requires a var" p1__28113#)) v6_l40)))


(def v9_l57 (pocket/cleanup!))


(def v10_l59 (defn transform [x] (* x 2)))


(def v12_l62 (deref (pocket/cached #'transform 10)))


(deftest t13_l64 (is (= v12_l62 20)))


(def v15_l67 (pocket/invalidate! #'transform 10))


(def v17_l71 (deref (pocket/cached #'transform 1)))


(def v18_l72 (deref (pocket/cached #'transform 2)))


(def v19_l74 (pocket/invalidate-fn! #'transform))


(def v21_l81 (pocket/cleanup!))


(def
 v22_l83
 (defn
  process-data
  [{:keys [data version]}]
  {:result (reduce + data), :version version}))


(def
 v24_l88
 (deref (pocket/cached #'process-data {:data [1 2 3], :version 1})))


(deftest t25_l90 (is (= v24_l88 {:result 6, :version 1})))


(def
 v27_l93
 (deref (pocket/cached #'process-data {:data [1 2 3], :version 2})))


(deftest t28_l95 (is (= v27_l93 {:result 6, :version 2})))


(def v30_l103 (pocket/cleanup!))


(def v32_l135 (pocket/cleanup!))


(def v33_l137 (def call-count (atom 0)))


(def v34_l139 (defn tracked-fn [x] (swap! call-count inc) (* x x)))


(def v36_l144 (reset! call-count 0))


(def
 v37_l146
 (let
  [result (deref (pocket/cached #'tracked-fn 5)) calls @call-count]
  {:result result, :calls calls}))


(deftest t38_l150 (is (= v37_l146 {:result 25, :calls 1})))


(def
 v40_l153
 (let
  [result (deref (pocket/cached #'tracked-fn 5)) calls @call-count]
  {:result result, :calls calls}))


(deftest t41_l157 (is (= v40_l153 {:result 25, :calls 1})))


(def v43_l165 (pocket/cleanup!))


(def v44_l166 (deref (pocket/cached #'transform 1)))


(def v45_l167 (deref (pocket/cached #'transform 2)))


(def v46_l168 (deref (pocket/cached #'tracked-fn 3)))


(def v48_l171 (count (pocket/cache-entries)))


(deftest t49_l173 (is (= v48_l171 3)))


(def v51_l176 (:total-entries (pocket/cache-stats)))


(deftest t52_l178 (is (= v51_l176 3)))


(def v54_l181 (kind/code (pocket/dir-tree)))


(def
 v56_l186
 (->
  (pocket/cache-entries)
  first
  :path
  (str "/meta.edn")
  slurp
  clojure.edn/read-string))


(def v58_l195 (pocket/cache-entries))


(def v60_l199 (pocket/cache-entries (str (ns-name *ns*) "/transform")))


(def v62_l205 (pocket/cleanup!))


(def v63_l207 (def pending-value (pocket/cached #'transform 99)))


(def v65_l210 (pr-str pending-value))


(deftest
 t66_l212
 (is ((fn* [p1__28114#] (re-find #":pending" p1__28114#)) v65_l210)))


(def v67_l214 (deref pending-value))


(def v69_l217 (pr-str pending-value))


(deftest
 t70_l219
 (is ((fn* [p1__28115#] (re-find #":cached" p1__28115#)) v69_l217)))


(def v72_l239 (pocket/cleanup!))


(def
 v73_l241
 (defn
  process-long-text
  [text]
  (str "Processed: " (count text) " chars")))


(def v74_l244 (def long-text (apply str (repeat 300 "x"))))


(def v75_l246 (deref (pocket/cached #'process-long-text long-text)))


(deftest
 t76_l248
 (is
  ((fn [result] (clojure.string/starts-with? result "Processed:"))
   v75_l246)))


(def v78_l252 (kind/code (pocket/dir-tree)))


(def
 v80_l257
 (->
  (pocket/cache-entries (str (ns-name *ns*) "/process-long-text"))
  first
  :fn-name))


(deftest
 t81_l261
 (is
  ((fn
    [fn-name]
    (and
     fn-name
     (clojure.string/ends-with? fn-name "/process-long-text")))
   v80_l257)))


(def v83_l291 (pocket/cleanup!))


(def v84_l293 (defn generate-data [n] (doall (range n))))


(def v85_l297 (deref (pocket/cached #'generate-data 5)))


(deftest t86_l299 (is (= v85_l297 [0 1 2 3 4])))


(def v88_l332 (pocket/cleanup!))
