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


(def v2_l68 (def cache-dir "/tmp/pocket-regression"))


(def v3_l70 (pocket/set-base-cache-dir! cache-dir))


(def v4_l72 (pocket/cleanup!))


(def
 v6_l84
 (defn
  make-regression-data
  "Generate a synthetic regression dataset.\n  `f` is a function from x to y (the ground truth).\n  Optional `outlier-fraction` (0–1) and `outlier-scale` inject\n  corrupted x values to simulate sensor glitches."
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


(def
 v8_l112
 (defn
  split-dataset
  "Split a dataset into train/test using holdout."
  [ds {:keys [seed]}]
  (first (tc/split->seq ds :holdout {:seed seed}))))


(def
 v10_l123
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
     (tc/add-columns ds {:x2 (tcc/sq x)})
     :trig
     (tc/add-columns ds {:sin-x (tcc/sin x), :cos-x (tcc/cos x)})
     :poly+trig
     (tc/add-columns
      ds
      {:x2 (tcc/sq x), :sin-x (tcc/sin x), :cos-x (tcc/cos x)}))
    (ds-mod/set-inference-target :y)))))


(def
 v12_l148
 (defn
  train-model
  "Train a model on a dataset."
  [train-ds model-spec]
  (ml/train train-ds model-spec)))


(def
 v13_l153
 (defn
  predict-and-rmse
  "Predict on test data and return RMSE."
  [test-ds model]
  (let
   [pred (ml/predict test-ds model)]
   (loss/rmse (:y test-ds) (:y pred)))))


(def
 v15_l169
 (defn nonlinear-fn "y = sin(x) · x" [x] (* (Math/sin x) x)))


(def
 v17_l189
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
 v18_l200
 (def
  cart-spec
  {:model-type :scicloj.ml.tribuo/regression,
   :tribuo-components
   [{:name "cart",
     :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
     :properties {:maxDepth "8"}}],
   :tribuo-trainer-name "cart"}))


(def
 v20_l227
 (def
  data-c
  (pocket/cached
   #'make-regression-data
   {:f #'nonlinear-fn, :n 500, :noise-sd 0.5, :seed 42})))


(def v21_l231 (tc/head (deref data-c)))


(def
 v23_l235
 (def split-c (pocket/cached #'split-dataset data-c {:seed 42})))


(def v25_l242 (def train-c (pocket/cached :train split-c)))


(def v26_l243 (def test-c (pocket/cached :test split-c)))


(def v28_l247 (def feature-sets [:raw :quadratic :trig :poly+trig]))


(def
 v30_l254
 (def
  prepared
  (into
   {}
   (for
    [fs feature-sets [role ds-c] [[:train train-c] [:test test-c]]]
    [[fs role] (pocket/cached #'prepare-features ds-c fs)]))))


(def
 v32_l265
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
 v34_l277
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


(def v35_l286 feature-results)


(deftest
 t36_l288
 (is
  ((fn
    [rows]
    (let
     [m (into {} (map (juxt (juxt :feature-set :model) :rmse) rows))]
     (and
      (> (m [:raw "sgd"]) 3.0)
      (< (m [:poly+trig "sgd"]) 2.0)
      (< (Math/abs (- (m [:raw "cart"]) (m [:trig "cart"]))) 0.5))))
   v35_l286)))


(def
 v38_l313
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


(def v40_l344 (def noise-levels [0.1 0.5 1.0 2.0 5.0]))


(def
 v41_l346
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


(def v42_l364 noise-results)


(deftest
 t43_l366
 (is
  ((fn
    [rows]
    (let
     [low
      (first
       (filter (fn* [p1__71627#] (= 0.1 (:noise-sd p1__71627#))) rows))
      high
      (first
       (filter
        (fn* [p1__71628#] (= 5.0 (:noise-sd p1__71628#)))
        rows))]
     (and
      (< (:cart-rmse low) (:sgd-rmse low))
      (> (:cart-rmse high) (:sgd-rmse high)))))
   v42_l364)))


(def
 v45_l388
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


(def v47_l404 (:total-entries (pocket/cache-stats)))


(deftest t48_l406 (is ((fn [n] (> n 30)) v47_l404)))


(def v49_l409 (:entries-per-fn (pocket/cache-stats)))


(def v51_l427 (pocket/cleanup!))


(def
 v53_l485
 (defn
  fit-outlier-threshold
  "Compute IQR-based clipping bounds for :x from training data.\n  Returns {:lower <bound> :upper <bound>}."
  [train-ds]
  (println "  Fitting outlier threshold from training data...")
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
 v54_l498
 (defn
  clip-outliers
  "Clip :x values using pre-computed threshold bounds."
  [ds threshold]
  (println
   "  Clipping outliers with bounds:"
   (select-keys threshold [:lower :upper]))
  (let
   [{:keys [lower upper]} threshold]
   (tc/add-column ds :x (-> (:x ds) (tcc/max lower) (tcc/min upper))))))


(def
 v55_l505
 (defn
  evaluate-model
  "Evaluate a model on test data."
  [test-ds model]
  (println "  Evaluating model...")
  (let
   [pred (ml/predict test-ds model)]
   {:rmse (loss/rmse (:y test-ds) (:y pred))})))


(def
 v57_l524
 (def
  c-fit-threshold
  (pocket/caching-fn #'fit-outlier-threshold {:storage :mem})))


(def
 v58_l527
 (def c-clip (pocket/caching-fn #'clip-outliers {:storage :mem})))


(def
 v59_l530
 (def c-prepare (pocket/caching-fn #'prepare-features {:storage :mem})))


(def v60_l533 (def c-train (pocket/caching-fn #'train-model)))


(def
 v61_l536
 (def c-evaluate (pocket/caching-fn #'evaluate-model {:storage :none})))


(def
 v63_l544
 (def
  dag-data-c
  (pocket/cached
   #'make-regression-data
   {:f #'nonlinear-fn,
    :n 200,
    :noise-sd 0.3,
    :seed 99,
    :outlier-fraction 0.1,
    :outlier-scale 15})))


(def
 v64_l549
 (def
  dag-split-c
  (pocket/cached #'split-dataset dag-data-c {:seed 99})))


(def v65_l552 (def dag-train-c (pocket/cached :train dag-split-c)))


(def v66_l553 (def dag-test-c (pocket/cached :test dag-split-c)))


(def v68_l559 (def threshold-c (c-fit-threshold dag-train-c)))


(def v69_l562 (def train-clipped-c (c-clip dag-train-c threshold-c)))


(def v70_l565 (def test-clipped-c (c-clip dag-test-c threshold-c)))


(def
 v71_l568
 (def train-prepped-c (c-prepare train-clipped-c :poly+trig)))


(def
 v72_l571
 (def test-prepped-c (c-prepare test-clipped-c :poly+trig)))


(def v73_l574 (def model-c (c-train train-prepped-c cart-spec)))


(def v74_l577 (def metrics-c (c-evaluate test-prepped-c model-c)))


(def v76_l591 (pocket/origin-story metrics-c))


(def v78_l599 (pocket/origin-story-graph metrics-c))


(def v80_l606 (pocket/origin-story-mermaid metrics-c))


(def v82_l610 (deref metrics-c))


(deftest
 t83_l612
 (is ((fn [m] (and (map? m) (contains? m :rmse))) v82_l610)))


(def
 v85_l622
 (let
  [noclip-train-c
   (c-prepare dag-train-c :poly+trig)
   noclip-test-c
   (c-prepare dag-test-c :poly+trig)
   noclip-model-c
   (c-train noclip-train-c cart-spec)
   noclip-metrics
   @(c-evaluate noclip-test-c noclip-model-c)
   clean-data-c
   (pocket/cached
    #'make-regression-data
    {:f #'nonlinear-fn, :n 200, :noise-sd 0.3, :seed 99})
   clean-split-c
   (pocket/cached #'split-dataset clean-data-c {:seed 99})
   clean-train-c
   (c-prepare (pocket/cached :train clean-split-c) :poly+trig)
   clean-test-c
   (c-prepare (pocket/cached :test clean-split-c) :poly+trig)
   clean-model-c
   (c-train clean-train-c cart-spec)
   clean-metrics
   @(c-evaluate clean-test-c clean-model-c)]
  {:clean clean-metrics,
   :outliers-no-clip noclip-metrics,
   :outliers-clipped @metrics-c}))


(deftest
 t86_l639
 (is
  ((fn
    [m]
    (< (:rmse (:outliers-clipped m)) (:rmse (:outliers-no-clip m))))
   v85_l622)))


(def
 v88_l657
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
 v90_l675
 (def
  experiments
  (for
   [noise-sd [0.3 0.5] feature-set [:raw :poly+trig] max-depth [4 8]]
   (pocket/cached
    #'run-pipeline
    {:noise-sd noise-sd,
     :feature-set feature-set,
     :max-depth max-depth}))))


(def v92_l686 (def comparison (pocket/compare-experiments experiments)))


(def v93_l689 (tc/dataset comparison))


(deftest
 t94_l691
 (is
  ((fn
    [ds]
    (and
     (= 8 (tc/row-count ds))
     (some #{:noise-sd} (tc/column-names ds))
     (some #{:feature-set} (tc/column-names ds))
     (some #{:max-depth} (tc/column-names ds))))
   v93_l689)))


(def
 v96_l704
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


(def v98_l763 (pocket/cleanup!))
