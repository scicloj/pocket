(ns
 pocket-book.regression-experiments-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [tablecloth.api :as tc]
  [tablecloth.column.api :as tcc]
  [tech.v3.dataset :as ds]
  [tech.v3.dataset.modelling :as ds-mod]
  [scicloj.metamorph.ml :as ml]
  [scicloj.metamorph.ml.loss :as loss]
  [scicloj.ml.tribuo]
  [clojure.test :refer [deftest is]]))


(def v2_l43 (def cache-dir "/tmp/pocket-regression"))


(def v3_l45 (pocket/set-base-cache-dir! cache-dir))


(def v4_l47 (pocket/cleanup!))


(def
 v6_l54
 (defn
  make-regression-data
  "Generate a synthetic regression dataset.\n  `f` is a function from x to y (the ground truth).\n  Returns a dataset with columns `:x` and `:y`."
  [f n noise-sd seed]
  (let
   [rng
    (java.util.Random. (long seed))
    xs
    (vec (repeatedly n (fn* [] (* 10.0 (.nextDouble rng)))))
    ys
    (mapv
     (fn
      [x]
      (+ (double (f x)) (* (double noise-sd) (.nextGaussian rng))))
     xs)]
   (-> (tc/dataset {:x xs, :y ys}) (ds-mod/set-inference-target :y)))))


(def
 v7_l67
 (defn
  prepare-features
  "Add derived columns to a dataset according to `feature-set`.\n  Supported feature sets:\n  - `:raw`       — no extra columns\n  - `:quadratic` — add x²\n  - `:trig`      — add sin(x) and cos(x)\n  - `:poly+trig` — add x², sin(x), and cos(x)"
  [ds feature-set]
  (let
   [x (:x ds)]
   (->
    (case
     feature-set
     :raw
     ds
     :quadratic
     (-> ds (tc/add-column :x2 (tcc/sq x)))
     :trig
     (->
      ds
      (tc/add-column :sin-x (tcc/sin x))
      (tc/add-column :cos-x (tcc/cos x)))
     :poly+trig
     (->
      ds
      (tc/add-column :x2 (tcc/sq x))
      (tc/add-column :sin-x (tcc/sin x))
      (tc/add-column :cos-x (tcc/cos x))))
    (ds-mod/set-inference-target :y)))))


(def
 v8_l89
 (defn
  train-model
  "Train a model on a dataset."
  [train-ds model-spec]
  (ml/train train-ds model-spec)))


(def
 v9_l94
 (defn
  predict-and-rmse
  "Predict on test data and return RMSE."
  [test-ds model]
  (let
   [pred (ml/predict test-ds model)]
   (loss/rmse (:y test-ds) (:y pred)))))


(def
 v11_l105
 (defn nonlinear-fn "y = sin(x) · x" [x] (* (Math/sin x) x)))


(def
 v13_l116
 (def
  linear-sgd-spec
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
 v14_l127
 (def
  cart-spec
  {:model-type :scicloj.ml.tribuo/regression,
   :tribuo-components
   [{:name "cart",
     :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
     :properties {:maxDepth "8"}}],
   :tribuo-trainer-name "cart"}))


(def
 v16_l144
 (def
  data
  @(pocket/cached #'make-regression-data #'nonlinear-fn 500 0.5 42)))


(def v17_l147 (tc/head data))


(def
 v19_l151
 (def split (first (tc/split->seq data :holdout {:seed 42}))))


(def v21_l156 (def feature-sets [:raw :quadratic :trig :poly+trig]))


(def
 v23_l163
 (def
  prepared
  (into
   {}
   (for
    [fs feature-sets role [:train :test]]
    [[fs role] @(pocket/cached #'prepare-features (role split) fs)]))))


(def
 v25_l174
 (def
  models
  (into
   {}
   (for
    [fs
     feature-sets
     [model-name spec]
     [[:sgd linear-sgd-spec] [:cart cart-spec]]]
    [[fs model-name]
     @(pocket/cached #'train-model (prepared [fs :train]) spec)]))))


(def
 v27_l186
 (def
  feature-results
  (vec
   (for
    [fs
     feature-sets
     [model-name _]
     [[:sgd linear-sgd-spec] [:cart cart-spec]]]
    {:feature-set fs,
     :model (name model-name),
     :rmse
     (predict-and-rmse
      (prepared [fs :test])
      (models [fs model-name]))}))))


(def v28_l195 feature-results)


(deftest
 t29_l197
 (is
  ((fn
    [rows]
    (let
     [m (into {} (map (juxt (juxt :feature-set :model) :rmse) rows))]
     (and
      (> (m [:raw "sgd"]) 3.0)
      (< (m [:poly+trig "sgd"]) 2.0)
      (< (Math/abs (- (m [:raw "cart"]) (m [:trig "cart"]))) 0.5))))
   v28_l195)))


(def
 v31_l215
 (let
  [test-ds
   (prepared [:raw :test])
   sgd-pred
   (:y
    (ml/predict
     (prepared [:poly+trig :test])
     (models [:poly+trig :sgd])))
   cart-pred
   (:y (ml/predict test-ds (models [:raw :cart])))
   xs
   (vec (:x test-ds))
   actuals
   (vec (:y test-ds))
   sgd-vals
   (vec sgd-pred)
   cart-vals
   (vec cart-pred)]
  (kind/plotly
   {:data
    [{:x xs,
      :y actuals,
      :mode "markers",
      :name "actual",
      :marker {:opacity 0.3, :color "gray"}}
     {:x xs,
      :y sgd-vals,
      :mode "markers",
      :name "Linear SGD (poly+trig)",
      :marker {:opacity 0.5, :color "steelblue"}}
     {:x xs,
      :y cart-vals,
      :mode "markers",
      :name "CART (raw)",
      :marker {:opacity 0.5, :color "tomato"}}],
    :layout {:xaxis {:title "x"}, :yaxis {:title "y"}}})))


(def v33_l241 (def noise-levels [0.1 0.5 1.0 2.0 5.0]))


(def
 v34_l243
 (def
  noise-results
  (vec
   (for
    [noise-sd noise-levels]
    (let
     [ds
      @(pocket/cached
        #'make-regression-data
        #'nonlinear-fn
        500
        noise-sd
        42)
      sp
      (first (tc/split->seq ds :holdout {:seed 42}))
      cart-train
      @(pocket/cached #'prepare-features (:train sp) :raw)
      cart-test
      @(pocket/cached #'prepare-features (:test sp) :raw)
      sgd-train
      @(pocket/cached #'prepare-features (:train sp) :poly+trig)
      sgd-test
      @(pocket/cached #'prepare-features (:test sp) :poly+trig)
      cart-model
      @(pocket/cached #'train-model cart-train cart-spec)
      sgd-model
      @(pocket/cached #'train-model sgd-train linear-sgd-spec)]
     {:noise-sd noise-sd,
      :cart-rmse (predict-and-rmse cart-test cart-model),
      :sgd-rmse (predict-and-rmse sgd-test sgd-model)})))))


(def v35_l259 noise-results)


(deftest
 t36_l261
 (is
  ((fn
    [rows]
    (let
     [low
      (first
       (filter (fn* [p1__98761#] (= 0.1 (:noise-sd p1__98761#))) rows))
      high
      (first
       (filter
        (fn* [p1__98762#] (= 5.0 (:noise-sd p1__98762#)))
        rows))]
     (and
      (< (:cart-rmse low) (:sgd-rmse low))
      (> (:cart-rmse high) (:sgd-rmse high)))))
   v35_l259)))


(def
 v38_l275
 (let
  [noise-sds
   (vec (map :noise-sd noise-results))
   cart-rmses
   (vec (map :cart-rmse noise-results))
   sgd-rmses
   (vec (map :sgd-rmse noise-results))]
  (kind/plotly
   {:data
    [{:x noise-sds, :y cart-rmses, :mode "lines+markers", :name "CART"}
     {:x noise-sds,
      :y sgd-rmses,
      :mode "lines+markers",
      :name "Linear SGD"}],
    :layout {:xaxis {:title "noise-sd"}, :yaxis {:title "rmse"}}})))


(def v40_l291 (:total-entries (pocket/cache-stats)))


(deftest t41_l293 (is ((fn [n] (> n 30)) v40_l291)))


(def v42_l296 (pocket/cache-entries))


(def v44_l312 (pocket/cleanup!))


(def
 v46_l349
 (defn
  compute-stats
  "Compute normalization statistics from training data.\n   Returns mean and std for each numeric column."
  [train-ds]
  (println "  Computing stats from training data...")
  (let
   [x-vals (vec (:x train-ds))]
   {:x-mean (/ (reduce + x-vals) (count x-vals)),
    :x-std
    (Math/sqrt
     (/
      (reduce
       +
       (map
        (fn*
         [p1__98763#]
         (*
          (- p1__98763# (/ (reduce + x-vals) (count x-vals)))
          (- p1__98763# (/ (reduce + x-vals) (count x-vals)))))
        x-vals))
      (count x-vals)))})))


(def
 v47_l361
 (defn
  normalize-with-stats
  "Normalize a dataset using pre-computed statistics."
  [ds stats]
  (println "  Normalizing with stats:" stats)
  (let
   [{:keys [x-mean x-std]} stats]
   (tc/add-column ds :x-norm (tcc// (tcc/- (:x ds) x-mean) x-std)))))


(def
 v48_l369
 (defn
  train-normalized-model
  "Train a model on normalized data."
  [train-ds model-spec]
  (println "  Training model on normalized data...")
  (ml/train train-ds model-spec)))


(def
 v49_l375
 (defn
  evaluate-model
  "Evaluate a model on test data."
  [test-ds model]
  (println "  Evaluating model...")
  (let
   [pred (ml/predict test-ds model)]
   {:rmse (loss/rmse (:y test-ds) (:y pred))})))


(def
 v51_l386
 (def
  dag-data
  @(pocket/cached #'make-regression-data #'nonlinear-fn 200 0.3 99)))


(def
 v52_l389
 (def dag-split (first (tc/split->seq dag-data :holdout {:seed 99}))))


(def
 v54_l395
 (def stats-c (pocket/cached #'compute-stats (:train dag-split))))


(def
 v55_l398
 (def
  train-norm-c
  (pocket/cached #'normalize-with-stats (:train dag-split) stats-c)))


(def
 v56_l401
 (def
  test-norm-c
  (pocket/cached #'normalize-with-stats (:test dag-split) stats-c)))


(def
 v57_l404
 (def
  model-c
  (pocket/cached #'train-normalized-model train-norm-c cart-spec)))


(def
 v58_l407
 (def metrics-c (pocket/cached #'evaluate-model test-norm-c model-c)))


(def v60_l421 (pocket/origin-story metrics-c))


(def v62_l429 (pocket/origin-story-graph metrics-c))


(def v64_l436 (pocket/origin-story-mermaid metrics-c))


(def v66_l440 (deref metrics-c))


(deftest
 t67_l442
 (is ((fn [m] (and (map? m) (contains? m :rmse))) v66_l440)))


(def
 v69_l452
 (defn
  run-pipeline
  "Run a complete pipeline with given hyperparameters."
  [{:keys [noise-sd feature-set max-depth]}]
  (let
   [ds
    (make-regression-data nonlinear-fn 200 noise-sd 42)
    sp
    (first (tc/split->seq ds :holdout {:seed 42}))
    train-prep
    (prepare-features (:train sp) feature-set)
    test-prep
    (prepare-features (:test sp) feature-set)
    spec
    {:model-type :scicloj.ml.tribuo/regression,
     :tribuo-components
     [{:name "cart",
       :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
       :properties {:maxDepth (str max-depth)}}],
     :tribuo-trainer-name "cart"}
    model
    (ml/train train-prep spec)
    pred
    (ml/predict test-prep model)]
   {:rmse (loss/rmse (:y test-prep) (:y pred))})))


(def
 v71_l470
 (def
  experiments
  (for
   [noise-sd [0.3 0.5] feature-set [:raw :poly+trig] max-depth [4 8]]
   (pocket/cached
    #'run-pipeline
    {:noise-sd noise-sd,
     :feature-set feature-set,
     :max-depth max-depth}))))


(def v73_l481 (def comparison (pocket/compare-experiments experiments)))


(def v74_l484 (tc/dataset comparison))


(deftest
 t75_l486
 (is
  ((fn
    [ds]
    (and
     (= 8 (tc/row-count ds))
     (some #{:noise-sd} (tc/column-names ds))
     (some #{:feature-set} (tc/column-names ds))
     (some #{:max-depth} (tc/column-names ds))))
   v74_l484)))


(def
 v77_l499
 (let
  [rows
   (map
    (fn
     [exp]
     (merge
      (select-keys exp [:noise-sd :feature-set :max-depth])
      (:result exp)))
    comparison)
   grouped
   (group-by (juxt :feature-set :noise-sd) rows)
   feature-colors
   {:raw "steelblue", :poly "tomato", :poly+trig "green"}]
  (kind/plotly
   {:data
    (for
     [[[feature-set noise-sd] pts]
      (sort-by first grouped)
      :let
      [max-depths (mapv :max-depth pts) rmses (mapv :rmse pts)]]
     {:x max-depths,
      :y rmses,
      :mode "markers",
      :name (str (name feature-set) " (noise=" noise-sd ")"),
      :legendgroup (name feature-set),
      :marker
      {:size (+ 8 (* 15 noise-sd)),
       :color (feature-colors feature-set)}}),
    :layout {:xaxis {:title "max-depth"}, :yaxis {:title "rmse"}}})))


(def v79_l521 (pocket/cleanup!))
