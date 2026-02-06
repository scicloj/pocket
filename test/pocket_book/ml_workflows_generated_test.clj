(ns
 pocket-book.ml-workflows-generated-test
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


(def v2_l66 (def cache-dir "/tmp/pocket-regression"))


(def v3_l68 (pocket/set-base-cache-dir! cache-dir))


(def v4_l70 (pocket/cleanup!))


(def
 v6_l82
 (defn
  make-regression-data
  "Generate a synthetic regression dataset.\n  `f` is a function from x to y (the ground truth).\n  Returns a dataset with columns `:x` and `:y`."
  [{:keys [f n noise-sd seed]}]
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
 v8_l100
 (defn
  split-dataset
  "Split a dataset into train/test using holdout."
  [ds {:keys [seed]}]
  (first (tc/split->seq ds :holdout {:seed seed}))))


(def
 v10_l111
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
 v12_l139
 (defn
  train-model
  "Train a model on a dataset."
  [train-ds model-spec]
  (ml/train train-ds model-spec)))


(def
 v13_l144
 (defn
  predict-and-rmse
  "Predict on test data and return RMSE."
  [test-ds model]
  (let
   [pred (ml/predict test-ds model)]
   (loss/rmse (:y test-ds) (:y pred)))))


(def
 v15_l160
 (defn nonlinear-fn "y = sin(x) · x" [x] (* (Math/sin x) x)))


(def
 v17_l180
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
 v18_l191
 (def
  cart-spec
  {:model-type :scicloj.ml.tribuo/regression,
   :tribuo-components
   [{:name "cart",
     :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
     :properties {:maxDepth "8"}}],
   :tribuo-trainer-name "cart"}))


(def
 v20_l218
 (def
  data-c
  (pocket/cached
   #'make-regression-data
   {:f #'nonlinear-fn, :n 500, :noise-sd 0.5, :seed 42})))


(def v21_l222 (tc/head (deref data-c)))


(def
 v23_l226
 (def split-c (pocket/cached #'split-dataset data-c {:seed 42})))


(def v25_l233 (def train-c (pocket/cached :train split-c)))


(def v26_l234 (def test-c (pocket/cached :test split-c)))


(def v28_l238 (def feature-sets [:raw :quadratic :trig :poly+trig]))


(def
 v30_l245
 (def
  prepared
  (into
   {}
   (for
    [fs feature-sets [role ds-c] [[:train train-c] [:test test-c]]]
    [[fs role] (pocket/cached #'prepare-features ds-c fs)]))))


(def
 v32_l256
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
     (pocket/cached #'train-model (prepared [fs :train]) spec)]))))


(def
 v34_l268
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
      @(prepared [fs :test])
      @(models [fs model-name]))}))))


(def v35_l277 feature-results)


(deftest
 t36_l279
 (is
  ((fn
    [rows]
    (let
     [m (into {} (map (juxt (juxt :feature-set :model) :rmse) rows))]
     (and
      (> (m [:raw "sgd"]) 3.0)
      (< (m [:poly+trig "sgd"]) 2.0)
      (< (Math/abs (- (m [:raw "cart"]) (m [:trig "cart"]))) 0.5))))
   v35_l277)))


(def
 v38_l304
 (let
  [test-ds
   @(prepared [:raw :test])
   sgd-pred
   (:y
    (ml/predict
     @(prepared [:poly+trig :test])
     @(models [:poly+trig :sgd])))
   cart-pred
   (:y (ml/predict test-ds @(models [:raw :cart])))
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


(def v40_l335 (def noise-levels [0.1 0.5 1.0 2.0 5.0]))


(def
 v41_l337
 (def
  noise-results
  (vec
   (for
    [noise-sd noise-levels]
    (let
     [data-c
      (pocket/cached
       #'make-regression-data
       {:f #'nonlinear-fn, :n 500, :noise-sd noise-sd, :seed 42})
      split-c
      (pocket/cached #'split-dataset data-c {:seed 42})
      train-c
      (pocket/cached :train split-c)
      test-c
      (pocket/cached :test split-c)
      cart-train
      (pocket/cached #'prepare-features train-c :raw)
      cart-test
      (pocket/cached #'prepare-features test-c :raw)
      sgd-train
      (pocket/cached #'prepare-features train-c :poly+trig)
      sgd-test
      (pocket/cached #'prepare-features test-c :poly+trig)
      cart-model
      (pocket/cached #'train-model cart-train cart-spec)
      sgd-model
      (pocket/cached #'train-model sgd-train linear-sgd-spec)]
     {:noise-sd noise-sd,
      :cart-rmse (predict-and-rmse @cart-test @cart-model),
      :sgd-rmse (predict-and-rmse @sgd-test @sgd-model)})))))


(def v42_l355 noise-results)


(deftest
 t43_l357
 (is
  ((fn
    [rows]
    (let
     [low
      (first
       (filter (fn* [p1__72349#] (= 0.1 (:noise-sd p1__72349#))) rows))
      high
      (first
       (filter
        (fn* [p1__72350#] (= 5.0 (:noise-sd p1__72350#)))
        rows))]
     (and
      (< (:cart-rmse low) (:sgd-rmse low))
      (> (:cart-rmse high) (:sgd-rmse high)))))
   v42_l355)))


(def
 v45_l379
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


(def v47_l395 (:total-entries (pocket/cache-stats)))


(deftest t48_l397 (is ((fn [n] (> n 30)) v47_l395)))


(def v49_l400 (pocket/cache-entries))


(def v51_l418 (pocket/cleanup!))


(def
 v53_l466
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
         [p1__72351#]
         (*
          (- p1__72351# (/ (reduce + x-vals) (count x-vals)))
          (- p1__72351# (/ (reduce + x-vals) (count x-vals)))))
        x-vals))
      (count x-vals)))})))


(def
 v54_l478
 (defn
  normalize-with-stats
  "Normalize a dataset using pre-computed statistics."
  [ds stats]
  (println "  Normalizing with stats:" stats)
  (let
   [{:keys [x-mean x-std]} stats]
   (tc/add-column ds :x-norm (tcc// (tcc/- (:x ds) x-mean) x-std)))))


(def
 v55_l486
 (defn
  train-normalized-model
  "Train a model on normalized data."
  [train-ds model-spec]
  (println "  Training model on normalized data...")
  (ml/train train-ds model-spec)))


(def
 v56_l492
 (defn
  evaluate-model
  "Evaluate a model on test data."
  [test-ds model]
  (println "  Evaluating model...")
  (let
   [pred (ml/predict test-ds model)]
   {:rmse (loss/rmse (:y test-ds) (:y pred))})))


(def
 v58_l511
 (def
  c-compute-stats
  (pocket/caching-fn #'compute-stats {:storage :mem})))


(def
 v59_l514
 (def
  c-normalize
  (pocket/caching-fn #'normalize-with-stats {:storage :mem})))


(def
 v60_l517
 (def c-train (pocket/caching-fn #'train-normalized-model)))


(def
 v61_l520
 (def c-evaluate (pocket/caching-fn #'evaluate-model {:storage :none})))


(def
 v63_l525
 (def
  dag-data-c
  (pocket/cached
   #'make-regression-data
   {:f #'nonlinear-fn, :n 200, :noise-sd 0.3, :seed 99})))


(def
 v64_l529
 (def
  dag-split-c
  (pocket/cached #'split-dataset dag-data-c {:seed 99})))


(def v65_l532 (def dag-train-c (pocket/cached :train dag-split-c)))


(def v66_l533 (def dag-test-c (pocket/cached :test dag-split-c)))


(def v68_l539 (def stats-c (c-compute-stats dag-train-c)))


(def v69_l542 (def train-norm-c (c-normalize dag-train-c stats-c)))


(def v70_l545 (def test-norm-c (c-normalize dag-test-c stats-c)))


(def v71_l548 (def model-c (c-train train-norm-c cart-spec)))


(def v72_l551 (def metrics-c (c-evaluate test-norm-c model-c)))


(def v74_l565 (pocket/origin-story metrics-c))


(def v76_l573 (pocket/origin-story-graph metrics-c))


(def v78_l580 (pocket/origin-story-mermaid metrics-c))


(def v80_l584 (deref metrics-c))


(deftest
 t81_l586
 (is ((fn [m] (and (map? m) (contains? m :rmse))) v80_l584)))


(def
 v83_l601
 (defn
  run-pipeline
  "Run a complete pipeline with given hyperparameters."
  [{:keys [noise-sd feature-set max-depth]}]
  (let
   [ds
    (make-regression-data
     {:f nonlinear-fn, :n 200, :noise-sd noise-sd, :seed 42})
    sp
    (split-dataset ds {:seed 42})
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
 v85_l619
 (def
  experiments
  (for
   [noise-sd [0.3 0.5] feature-set [:raw :poly+trig] max-depth [4 8]]
   (pocket/cached
    #'run-pipeline
    {:noise-sd noise-sd,
     :feature-set feature-set,
     :max-depth max-depth}))))


(def v87_l630 (def comparison (pocket/compare-experiments experiments)))


(def v88_l633 (tc/dataset comparison))


(deftest
 t89_l635
 (is
  ((fn
    [ds]
    (and
     (= 8 (tc/row-count ds))
     (some #{:noise-sd} (tc/column-names ds))
     (some #{:feature-set} (tc/column-names ds))
     (some #{:max-depth} (tc/column-names ds))))
   v88_l633)))


(def
 v91_l648
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
    (vec
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
        :color (feature-colors feature-set)}})),
    :layout {:xaxis {:title "max-depth"}, :yaxis {:title "rmse"}}})))


(def v93_l701 (pocket/cleanup!))
