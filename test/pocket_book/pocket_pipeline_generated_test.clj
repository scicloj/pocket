(ns
 pocket-book.pocket-pipeline-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [scicloj.metamorph.core :as mm]
  [tablecloth.api :as tc]
  [tablecloth.column.api :as tcc]
  [tech.v3.dataset.modelling :as ds-mod]
  [scicloj.metamorph.ml :as ml]
  [scicloj.metamorph.ml.loss :as loss]
  [scicloj.ml.tribuo]
  [clojure.test :refer [deftest is]]))


(def v3_l90 (pocket-book.logging/set-slf4j-level! :info))


(def v4_l92 (def cache-dir "/tmp/pocket-metamorph"))


(def v5_l94 (pocket/set-base-cache-dir! cache-dir))


(def v6_l96 (pocket/cleanup!))


(def
 v8_l108
 (defn
  make-regression-data
  "Generate synthetic regression data: y = f(x) + noise.\n  Optional outlier injection on x values."
  [{:keys [f n noise-sd seed outlier-fraction outlier-scale],
    :or {outlier-fraction 0, outlier-scale 10}}]
  (let
   [rng
    (java.util.Random. (long seed))
    xs
    (vec (repeatedly n (fn* [] (* 10.0 (.nextDouble rng)))))
    xs-final
    (if
     (pos? outlier-fraction)
     (let
      [out-rng (java.util.Random. (+ (long seed) 7919))]
      (mapv
       (fn
        [x]
        (if
         (< (.nextDouble out-rng) outlier-fraction)
         (+ x (* (double outlier-scale) (.nextGaussian out-rng)))
         x))
       xs))
     xs)
    ys
    (mapv
     (fn
      [x]
      (+ (double (f x)) (* (double noise-sd) (.nextGaussian rng))))
     xs)]
   (->
    (tc/dataset {:x xs-final, :y ys})
    (ds-mod/set-inference-target :y)))))


(def v9_l129 (defn nonlinear-fn [x] (* (Math/sin x) x)))


(def
 v10_l131
 (defn
  split-dataset
  "Split into train/test using holdout."
  [ds {:keys [seed]}]
  (first (tc/split->seq ds :holdout {:seed seed}))))


(def
 v11_l136
 (defn
  prepare-features
  "Add derived columns: :raw (none), :poly+trig (x², sin, cos)."
  [ds feature-set]
  (let
   [x (:x ds)]
   (->
    (case
     feature-set
     :raw
     ds
     :poly+trig
     (tc/add-columns
      ds
      {:x2 (tcc/sq x), :sin-x (tcc/sin x), :cos-x (tcc/cos x)}))
    (ds-mod/set-inference-target :y)))))


(def
 v12_l147
 (defn
  fit-outlier-threshold
  "Compute IQR-based clipping bounds for :x from training data."
  [train-ds]
  (let
   [xs
    (sort (vec (:x train-ds)))
    n
    (count xs)
    q1
    (nth xs (int (* 0.25 n)))
    q3
    (nth xs (int (* 0.75 n)))
    iqr
    (- q3 q1)]
   {:lower (- q1 (* 1.5 iqr)), :upper (+ q3 (* 1.5 iqr))})))


(def
 v13_l158
 (defn
  clip-outliers
  "Clip :x values using pre-computed threshold bounds."
  [ds threshold]
  (let
   [{:keys [lower upper]} threshold]
   (tc/add-column ds :x (-> (:x ds) (tcc/max lower) (tcc/min upper))))))


(def
 v14_l164
 (defn
  train-model
  "Train a model on a prepared dataset."
  [train-ds model-spec]
  (ml/train train-ds model-spec)))


(def
 v15_l169
 (defn
  predict-model
  "Predict on test data using a trained model."
  [test-ds model]
  (ml/predict test-ds model)))


(def
 v17_l176
 (def
  cart-spec
  {:model-type :scicloj.ml.tribuo/regression,
   :tribuo-components
   [{:name "cart",
     :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
     :properties {:maxDepth "8"}}],
   :tribuo-trainer-name "cart"}))


(def
 v18_l183
 (def
  linear-sgd-spec
  {:model-type :scicloj.ml.tribuo/regression,
   :tribuo-components
   [{:name "squared",
     :type "org.tribuo.regression.sgd.objectives.SquaredLoss"}
    {:name "linear-sgd",
     :type "org.tribuo.regression.sgd.linear.LinearSGDTrainer",
     :properties
     {:objective "squared", :epochs "50", :loggingInterval "10000"}}],
   :tribuo-trainer-name "linear-sgd"}))


(def
 v20_l209
 (def
  c-fit-outlier-threshold
  (pocket/caching-fn #'fit-outlier-threshold {:storage :mem})))


(def
 v21_l212
 (def
  c-clip-outliers
  (pocket/caching-fn #'clip-outliers {:storage :mem})))


(def
 v22_l215
 (def
  c-prepare-features
  (pocket/caching-fn #'prepare-features {:storage :mem})))


(def v23_l218 (def c-train-model (pocket/caching-fn #'train-model)))


(def
 v24_l221
 (def
  c-predict-model
  (pocket/caching-fn #'predict-model {:storage :mem})))


(def
 v26_l248
 (defn
  pocket-fitted
  "Create a stateful pipeline step from fit and apply functions.\n  In :fit mode, fits parameters from data and applies them.\n  In :transform mode, applies previously fitted parameters."
  [fit-caching-fn apply-caching-fn]
  (fn
   [{:metamorph/keys [data mode id], :as ctx}]
   (case
    mode
    :fit
    (let
     [fitted (fit-caching-fn data)]
     (->
      ctx
      (assoc id fitted)
      (assoc :metamorph/data (apply-caching-fn data fitted))))
    :transform
    (assoc ctx :metamorph/data (apply-caching-fn data (get ctx id)))))))


(def
 v28_l267
 (defn
  pocket-model
  "Create a model pipeline step. Trains in :fit mode,\n  predicts in :transform mode. Like ml/model."
  [model-spec]
  (fn
   [{:metamorph/keys [data mode id], :as ctx}]
   (case
    mode
    :fit
    (assoc ctx id (c-train-model data model-spec))
    :transform
    (let
     [model (get ctx id)]
     (assoc
      ctx
      :target
      data
      :metamorph/data
      (c-predict-model data model)))))))


(def
 v30_l285
 (def
  data-c
  (pocket/cached
   #'make-regression-data
   {:f #'nonlinear-fn,
    :n 500,
    :noise-sd 0.5,
    :seed 42,
    :outlier-fraction 0.1,
    :outlier-scale 15})))


(def
 v31_l290
 (def split-c (pocket/cached #'split-dataset data-c {:seed 42})))


(def v32_l291 (def train-c (pocket/cached :train split-c)))


(def v33_l292 (def test-c (pocket/cached :test split-c)))


(def
 v34_l294
 (def
  pipe-cart
  (mm/pipeline
   #:metamorph{:id :clip}
   (pocket-fitted c-fit-outlier-threshold c-clip-outliers)
   #:metamorph{:id :prep}
   (mm/lift c-prepare-features :poly+trig)
   #:metamorph{:id :model}
   (pocket-model cart-spec))))


(def v36_l302 (def fit-ctx (mm/fit-pipe train-c pipe-cart)))


(def
 v38_l306
 (def transform-ctx (mm/transform-pipe test-c pipe-cart fit-ctx)))


(def
 v40_l310
 (->
  fit-ctx
  :model
  deref
  (update :model-data dissoc :model-as-bytes)
  kind/pprint))


(deftest t41_l316 (is ((fn [model] (map? model)) v40_l310)))


(def v43_l321 (tc/head (deref (:metamorph/data transform-ctx))))


(def
 v45_l325
 (loss/rmse
  (:y @(:target transform-ctx))
  (:y @(:metamorph/data transform-ctx))))


(deftest t46_l328 (is ((fn [rmse] (< rmse 5.0)) v45_l325)))


(def
 v48_l338
 (defn
  compute-loss
  "Compute RMSE between actual and predicted :y columns."
  [actual-ds predicted-ds]
  (loss/rmse (:y actual-ds) (:y predicted-ds))))


(def
 v49_l343
 (def
  c-compute-loss
  (pocket/caching-fn #'compute-loss {:storage :mem})))


(def
 v51_l349
 (def
  train-transform-ctx
  (mm/transform-pipe train-c pipe-cart fit-ctx)))


(def
 v53_l353
 (def
  train-loss-c
  (c-compute-loss
   (:target train-transform-ctx)
   (:metamorph/data train-transform-ctx))))


(def
 v54_l357
 (def
  test-loss-c
  (c-compute-loss
   (:target transform-ctx)
   (:metamorph/data transform-ctx))))


(def
 v56_l363
 (defn
  report
  "Gather train and test loss into a summary map."
  [train-loss test-loss]
  {:train-rmse train-loss, :test-rmse test-loss}))


(def
 v57_l369
 (def c-report (pocket/caching-fn #'report {:storage :mem})))


(def v58_l371 (def summary-c (c-report train-loss-c test-loss-c)))


(def v59_l373 (deref summary-c))


(deftest
 t60_l375
 (is
  ((fn
    [{:keys [train-rmse test-rmse]}]
    (and (< train-rmse test-rmse) (< test-rmse 5.0)))
   v59_l373)))


(def v62_l384 (pocket/origin-story-mermaid summary-c))


(def v63_l385 (pocket/cleanup!))


(def
 v65_l394
 (defn
  nth-split-train
  "Extract the train set of the nth split."
  [ds split-method split-params idx]
  (:train (nth (tc/split->seq ds split-method split-params) idx))))


(def
 v66_l399
 (defn
  nth-split-test
  "Extract the test set of the nth split."
  [ds split-method split-params idx]
  (:test (nth (tc/split->seq ds split-method split-params) idx))))


(def
 v67_l404
 (defn-
  n-splits
  "Derive the number of splits from the method and params,\n  without materializing the dataset."
  [split-method split-params]
  (case
   split-method
   :kfold
   (:k split-params 5)
   :holdout
   1
   :bootstrap
   (:repeats split-params 1)
   :loo
   (throw
    (ex-info
     "pocket-splits does not support :loo (needs dataset size)"
     {})))))


(def
 v68_l414
 (defn
  pocket-splits
  "Create k-fold splits as Cached references.\n  Returns [{:train Cached, :test Cached, :idx int} ...]."
  [data-c split-method split-params]
  (vec
   (for
    [idx (range (n-splits split-method split-params))]
    {:train
     (pocket/cached
      #'nth-split-train
      data-c
      split-method
      split-params
      idx),
     :test
     (pocket/cached
      #'nth-split-test
      data-c
      split-method
      split-params
      idx),
     :idx idx}))))


(def
 v70_l433
 (defn
  pocket-evaluate-pipelines
  "Evaluate pipelines across k-fold splits.\n  Like ml/evaluate-pipelines, but built on Cached references.\n  \n  `pipelines` is a seq of pipeline functions (from mm/pipeline).\n  `metric-fn` takes (test-ds, prediction-ds) and returns a number."
  [data-c split-method split-params pipelines metric-fn]
  (let
   [splits (pocket-splits data-c split-method split-params)]
   (vec
    (for
     [pipe-fn pipelines {:keys [train test idx]} splits]
     (let
      [fit-ctx
       (mm/fit-pipe train pipe-fn)
       transform-ctx
       (mm/transform-pipe test pipe-fn fit-ctx)
       metric
       (metric-fn
        @(:target transform-ctx)
        @(:metamorph/data transform-ctx))]
      {:split-idx idx, :metric metric, :model (:model fit-ctx)}))))))


(def
 v72_l457
 (defn
  rmse-metric
  "Compute RMSE between actual and predicted :y columns."
  [test-ds pred-ds]
  (loss/rmse (:y test-ds) (:y pred-ds))))


(def
 v73_l462
 (defn
  make-pipe
  [{:keys [feature-set model-spec]}]
  (mm/pipeline
   #:metamorph{:id :clip}
   (pocket-fitted c-fit-outlier-threshold c-clip-outliers)
   #:metamorph{:id :prep}
   (mm/lift c-prepare-features feature-set)
   #:metamorph{:id :model}
   (pocket-model model-spec))))


(def
 v74_l468
 (def
  configs
  [{:feature-set :poly+trig, :model-spec cart-spec}
   {:feature-set :raw, :model-spec cart-spec}
   {:feature-set :poly+trig, :model-spec linear-sgd-spec}]))


(def
 v75_l473
 (def
  results
  (pocket-evaluate-pipelines
   data-c
   :kfold
   {:k 3, :seed 42}
   (mapv make-pipe configs)
   rmse-metric)))


(def v77_l480 (count results))


(deftest t78_l482 (is ((fn [n] (= n 9)) v77_l480)))


(def
 v80_l487
 (def
  summary
  (let
   [grouped (partition 3 results)]
   (mapv
    (fn
     [config rs]
     {:feature-set (:feature-set config),
      :model-type (-> config :model-spec :tribuo-trainer-name),
      :mean-rmse (tcc/mean (map :metric rs))})
    configs
    grouped))))


(def v81_l495 (tc/dataset summary))


(deftest t82_l497 (is ((fn [ds] (= 3 (tc/row-count ds))) v81_l495)))


(def
 v84_l502
 (def
  results-2
  (pocket-evaluate-pipelines
   data-c
   :kfold
   {:k 3, :seed 42}
   (mapv make-pipe configs)
   rmse-metric)))


(def v85_l507 (= (mapv :metric results) (mapv :metric results-2)))


(deftest t86_l509 (is ((fn [eq] (true? eq)) v85_l507)))


(def v88_l519 (pocket/cleanup!))


(def
 v89_l521
 (def
  sweep-configs
  (vec
   (for
    [depth [4 6 8 12] fs [:raw :poly+trig]]
    {:feature-set fs,
     :model-spec
     {:model-type :scicloj.ml.tribuo/regression,
      :tribuo-components
      [{:name "cart",
        :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
        :properties {:maxDepth (str depth)}}],
      :tribuo-trainer-name "cart"}}))))


(def
 v90_l531
 (def
  sweep-results
  (pocket-evaluate-pipelines
   data-c
   :kfold
   {:k 3, :seed 42}
   (mapv make-pipe sweep-configs)
   rmse-metric)))


(def v92_l538 (count sweep-results))


(deftest t93_l540 (is ((fn [n] (= n 24)) v92_l538)))


(def
 v95_l545
 (def
  sweep-summary
  (let
   [grouped (partition 3 sweep-results)]
   (->>
    (mapv
     (fn
      [config rs]
      {:depth
       (->
        config
        :model-spec
        :tribuo-components
        first
        :properties
        :maxDepth),
       :feature-set (:feature-set config),
       :mean-rmse (tcc/mean (map :metric rs))})
     sweep-configs
     grouped)
    (sort-by :mean-rmse)))))


(def v96_l555 (tc/dataset sweep-summary))


(deftest t97_l557 (is ((fn [ds] (= 8 (tc/row-count ds))) v96_l555)))


(def
 v99_l572
 (pocket/origin-story-mermaid (:model (first sweep-results))))


(def v101_l638 (pocket/cleanup!))
