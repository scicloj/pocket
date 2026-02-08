(ns
 pocket-book.pocket-model-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [tablecloth.api :as tc]
  [tablecloth.column.api :as tcc]
  [tech.v3.dataset.modelling :as ds-mod]
  [tech.v3.dataset.column-filters :as cf]
  [scicloj.metamorph.ml :as ml]
  [scicloj.metamorph.ml.loss :as loss]
  [scicloj.metamorph.ml.regression]
  [scicloj.metamorph.core :as mm]
  [scicloj.ml.tribuo]
  [clojure.test :refer [deftest is]]))


(def v2_l83 (def cache-dir "/tmp/pocket-model"))


(def v3_l85 (pocket/set-base-cache-dir! cache-dir))


(def v4_l87 (pocket/cleanup!))


(def
 v6_l101
 (defn
  pocket-model
  "Drop-in replacement for ml/model that caches training via Pocket.\n  Falls back to uncached training if serialization fails."
  [options]
  (fn
   [{:metamorph/keys [id data mode], :as ctx}]
   (case
    mode
    :fit
    (let
     [model
      (try
       (deref (pocket/cached #'ml/train data options))
       (catch Exception _e (ml/train data options)))]
     (assoc
      ctx
      id
      (assoc
       model
       :scicloj.metamorph.ml/unsupervised?
       (get (ml/options->model-def options) :unsupervised? false))))
    :transform
    (let
     [model (get ctx id)]
     (if
      (get model :scicloj.metamorph.ml/unsupervised?)
      ctx
      (->
       ctx
       (update
        id
        assoc
        :scicloj.metamorph.ml/feature-ds
        (cf/feature data)
        :scicloj.metamorph.ml/target-ds
        (cf/target data))
       (assoc :metamorph/data (ml/predict data model)))))))))


(def
 v8_l132
 (def
  ds
  (->
   (let
    [rng (java.util.Random. 42)]
    (tc/dataset
     {:x (vec (repeatedly 200 (fn* [] (* 10.0 (.nextDouble rng))))),
      :y
      (vec
       (repeatedly
        200
        (fn*
         []
         (+
          (* 3.0 (* 10.0 (.nextDouble rng)))
          (* 2.0 (.nextGaussian rng))))))}))
   (ds-mod/set-inference-target :y))))


(def v9_l139 (def splits (tc/split->seq ds :kfold {:k 3, :seed 42})))


(def v10_l141 (count splits))


(deftest t11_l143 (is ((fn [n] (= n 3)) v10_l141)))


(def
 v13_l154
 (def
  cart-spec
  {:model-type :scicloj.ml.tribuo/regression,
   :tribuo-components
   [{:name "cart",
     :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
     :properties {:maxDepth "8"}}],
   :tribuo-trainer-name "cart"}))


(def
 v14_l161
 (def
  pipe-cart
  (mm/pipeline #:metamorph{:id :model} (pocket-model cart-spec))))


(def
 v16_l168
 (def
  results-1
  (ml/evaluate-pipelines
   [pipe-cart]
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def
 v17_l177
 (mapv
  (fn* [p1__117405#] (-> p1__117405# :test-transform :metric))
  (flatten results-1)))


(deftest
 t18_l179
 (is
  ((fn [ms] (every? (fn* [p1__117406#] (< p1__117406# 15)) ms))
   v17_l177)))


(def v20_l184 (pocket/cache-stats))


(deftest
 t21_l186
 (is ((fn [stats] (= 3 (:total-entries stats))) v20_l184)))


(def
 v23_l191
 (def
  results-2
  (ml/evaluate-pipelines
   [pipe-cart]
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def
 v24_l200
 (=
  (mapv
   (fn* [p1__117407#] (-> p1__117407# :test-transform :metric))
   (flatten results-1))
  (mapv
   (fn* [p1__117408#] (-> p1__117408# :test-transform :metric))
   (flatten results-2))))


(deftest t25_l203 (is ((fn [eq] (true? eq)) v24_l200)))


(def v27_l213 (pocket/cleanup!))


(def
 v28_l215
 (defn
  cart-pipe
  [max-depth]
  (mm/pipeline
   #:metamorph{:id :model}
   (pocket-model
    {:model-type :scicloj.ml.tribuo/regression,
     :tribuo-components
     [{:name "cart",
       :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
       :properties {:maxDepth (str max-depth)}}],
     :tribuo-trainer-name "cart"}))))


(def
 v30_l227
 (def
  batch-1
  (ml/evaluate-pipelines
   (mapv cart-pipe [4 8 12])
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def v32_l238 (pocket/cache-stats))


(deftest
 t33_l240
 (is ((fn [stats] (= 9 (:total-entries stats))) v32_l238)))


(def
 v35_l246
 (def
  batch-2
  (ml/evaluate-pipelines
   (mapv cart-pipe [4 6 8 10 12 16])
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def v37_l257 (pocket/cache-stats))


(deftest
 t38_l259
 (is ((fn [stats] (= 18 (:total-entries stats))) v37_l257)))


(def
 v40_l264
 (let
  [depths
   [4 6 8 10 12 16]
   means
   (mapv
    (fn
     [pipeline-results]
     (tcc/mean
      (map
       (fn* [p1__117409#] (-> p1__117409# :test-transform :metric))
       pipeline-results)))
    batch-2)]
  (tc/dataset {:depth depths, :mean-rmse means})))


(deftest t41_l270 (is ((fn [ds] (= 6 (tc/row-count ds))) v40_l264)))


(def v43_l280 (pocket/cleanup!))


(def
 v44_l282
 (def
  sgd-spec
  {:model-type :scicloj.ml.tribuo/regression,
   :tribuo-components
   [{:name "squared",
     :type "org.tribuo.regression.sgd.objectives.SquaredLoss"}
    {:name "trainer",
     :type "org.tribuo.regression.sgd.linear.LinearSGDTrainer",
     :properties
     {:objective "squared", :epochs "50", :loggingInterval "10000"}}],
   :tribuo-trainer-name "trainer"}))


(def
 v45_l293
 (def
  multi-results
  (ml/evaluate-pipelines
   [(mm/pipeline #:metamorph{:id :model} (pocket-model cart-spec))
    (mm/pipeline #:metamorph{:id :model} (pocket-model sgd-spec))
    (mm/pipeline
     #:metamorph{:id :model}
     (pocket-model {:model-type :fastmath/ols}))]
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def v47_l306 (pocket/cache-stats))


(deftest
 t48_l308
 (is ((fn [stats] (= 9 (:total-entries stats))) v47_l306)))


(def
 v50_l313
 (let
  [model-names
   ["CART" "SGD" "fastmath-OLS"]
   means
   (mapv
    (fn
     [pipeline-results]
     (tcc/mean
      (map
       (fn* [p1__117410#] (-> p1__117410# :test-transform :metric))
       pipeline-results)))
    multi-results)]
  (tc/dataset {:model model-names, :mean-rmse means})))


(deftest t51_l319 (is ((fn [ds] (= 3 (tc/row-count ds))) v50_l313)))


(def v53_l331 (pocket/cleanup!))


(def
 v54_l333
 (def
  fallback-results
  (ml/evaluate-pipelines
   [(mm/pipeline #:metamorph{:id :model} (pocket-model cart-spec))
    (mm/pipeline
     #:metamorph{:id :model}
     (pocket-model {:model-type :metamorph.ml/ols}))]
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def v56_l348 (pocket/cache-stats))


(deftest
 t57_l350
 (is
  ((fn
    [stats]
    (=
     3
     (get-in stats [:entries-per-fn "scicloj.metamorph.ml/train"])))
   v56_l348)))


(def
 v59_l355
 (let
  [model-names
   ["CART" "OLS-fallback"]
   means
   (mapv
    (fn
     [pipeline-results]
     (tcc/mean
      (map
       (fn* [p1__117411#] (-> p1__117411# :test-transform :metric))
       pipeline-results)))
    fallback-results)]
  (tc/dataset {:model model-names, :mean-rmse means})))


(deftest t60_l361 (is ((fn [ds] (= 2 (tc/row-count ds))) v59_l355)))


(def v62_l371 (pocket/cleanup!))


(def
 v64_l374
 (def
  persist-results-1
  (ml/evaluate-pipelines
   [(mm/pipeline #:metamorph{:id :model} (pocket-model cart-spec))]
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def v66_l384 (pocket/clear-mem-cache!))


(def
 v68_l387
 (def
  persist-results-2
  (ml/evaluate-pipelines
   [(mm/pipeline #:metamorph{:id :model} (pocket-model cart-spec))]
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def
 v70_l397
 (=
  (mapv
   (fn* [p1__117412#] (-> p1__117412# :test-transform :metric))
   (flatten persist-results-1))
  (mapv
   (fn* [p1__117413#] (-> p1__117413# :test-transform :metric))
   (flatten persist-results-2))))


(deftest t71_l400 (is ((fn [eq] (true? eq)) v70_l397)))


(def v73_l430 (pocket/cleanup!))
