
;; # 🚧 Draft: Full Pocket integration for ML pipelines
;;
;; **Last modified: 2026-02-08**
;;
;; The previous chapter (`pocket-model`) showed how to cache model
;; training in a [metamorph.ml](https://github.com/scicloj/metamorph.ml)
;; pipeline by swapping one step. That approach is simple — just
;; replace `ml/model` with `pocket-model` — but only the training
;; step is cached through Pocket.
;;
;; This chapter explores a deeper integration: building the **entire
;; pipeline** as a chain of `pocket/caching-fn` calls. Every step —
;; data splitting, feature engineering, outlier clipping, training,
;; evaluation — becomes a cached node in a DAG, giving us:
;;
;; - **Per-step storage control** — choose `:mem`, `:mem+disk`, or
;;   `:none` for each step independently
;; - **Full provenance** — `origin-story` traces any result back to
;;   the scalar parameters that produced it
;; - **Disk persistence** — cached models and intermediate results
;;   survive JVM restarts
;; - **Concurrent dedup** — same computation runs once across threads
;;
;; We then add a thin evaluation loop on top — `pocket-evaluate-pipelines`
;; — that provides cross-validation and model comparison, similar to
;; metamorph.ml's `evaluate-pipelines` but built on Pocket's DAG.
;;
;; ## Background
;;
;; [metamorph.ml](https://github.com/scicloj/metamorph.ml) is the
;; Scicloj library for machine learning pipelines. It builds on
;; [metamorph](https://github.com/scicloj/metamorph), a
;; data-transformation framework where each step is a function that
;; takes a context map and returns an updated one. Metamorph
;; distinguishes two modes — `:fit` (learn from training data) and
;; `:transform` (apply to new data) — so a pipeline can be trained
;; once and reused for prediction.
;;
;; On top of this, metamorph.ml adds model training/prediction,
;; cross-validation (`evaluate-pipelines`), loss functions, and
;; hyperparameter search.
;;
;; ## How this chapter relates to others
;;
;; The [ML Workflows](ml_workflows.html) chapter demonstrates Pocket
;; caching with plain functions — `pocket/cached` calls wired into a
;; DAG. This chapter uses the same pipeline functions and the same
;; DAG approach, but adds **cross-validation and model comparison**
;; on top, providing functionality similar to metamorph.ml's
;; `evaluate-pipelines`.
;;
;; The [pocket-model](pocket_model.html) chapter takes the opposite
;; approach: it plugs into metamorph.ml's existing pipeline machinery
;; with a single drop-in replacement. Simpler to adopt, but only the
;; training step is cached through Pocket.
;;
;; | | pocket-model | This chapter |
;; |--|-------------|-------------|
;; | Integration effort | One-line change | Build pipeline as DAG |
;; | What's cached | Training only | Every step |
;; | Provenance | Training step only | Full DAG |
;; | Storage control | Global | Per-step |
;; | Evaluation | metamorph.ml's `evaluate-pipelines` | `pocket-evaluate-pipelines` (loops over `Cached` references) |

;; ## Setup

(ns pocket-book.pocket-pipeline
  (:require
   ;; Logging setup for this chapter (see Logging chapter):
   [pocket-book.logging]
   ;; Pocket API:
   [scicloj.pocket :as pocket]
   ;; Annotating kinds of visualizations:
   [scicloj.kindly.v4.kind :as kind]
   ;; Metamorph pipeline tools:
   [scicloj.metamorph.core :as mm]
   ;; Data processing:
   [tablecloth.api :as tc]
   [tablecloth.column.api :as tcc]
   [tech.v3.dataset.modelling :as ds-mod]
   ;; Machine learning:
   [scicloj.metamorph.ml :as ml]
   [scicloj.metamorph.ml.loss :as loss]
   [scicloj.ml.tribuo]))

;; Override the default log level from debug to info. This notebook
;; has many cached steps and debug output (cache hits, writes) would
;; overwhelm the rendered output. Info level shows cache misses,
;; invalidation, and cleanup — enough to see when computation happens.
(pocket-book.logging/set-slf4j-level! :info)

(def cache-dir "/tmp/pocket-metamorph")

(pocket/set-base-cache-dir! cache-dir)

(pocket/cleanup!)

;; ---

;; ## Pipeline functions
;;
;; These are plain Clojure functions — each takes data in and returns
;; data out. They know nothing about caching or about metamorph's
;; context maps and fit/transform modes. Pocket will wrap them with
;; `caching-fn` later to add caching; the evaluation loop will call
;; them across folds to add cross-validation.

(defn make-regression-data
  "Generate synthetic regression data: y = f(x) + noise.
  Optional outlier injection on x values."
  [{:keys [f n noise-sd seed outlier-fraction outlier-scale]
    :or {outlier-fraction 0 outlier-scale 10}}]
  (let [rng (java.util.Random. (long seed))
        xs (vec (repeatedly n #(* 10.0 (.nextDouble rng))))
        xs-final (if (pos? outlier-fraction)
                   (let [out-rng (java.util.Random. (+ (long seed) 7919))]
                     (mapv (fn [x]
                             (if (< (.nextDouble out-rng) outlier-fraction)
                               (+ x (* (double outlier-scale) (.nextGaussian out-rng)))
                               x))
                           xs))
                   xs)
        ys (mapv (fn [x] (+ (double (f x))
                            (* (double noise-sd) (.nextGaussian rng))))
                 xs)]
    (-> (tc/dataset {:x xs-final :y ys})
        (ds-mod/set-inference-target :y))))

(defn nonlinear-fn [x] (* (Math/sin x) x))

(defn split-dataset
  "Split into train/test using holdout."
  [ds {:keys [seed]}]
  (first (tc/split->seq ds :holdout {:seed seed})))

(defn prepare-features
  "Add derived columns: :raw (none), :poly+trig (x², sin, cos)."
  [ds feature-set]
  (let [x (:x ds)]
    (-> (case feature-set
          :raw ds
          :poly+trig (tc/add-columns ds {:x2 (tcc/sq x)
                                         :sin-x (tcc/sin x)
                                         :cos-x (tcc/cos x)}))
        (ds-mod/set-inference-target :y))))

(defn fit-outlier-threshold
  "Compute IQR-based clipping bounds for :x from training data."
  [train-ds]
  (let [xs (sort (vec (:x train-ds)))
        n (count xs)
        q1 (nth xs (int (* 0.25 n)))
        q3 (nth xs (int (* 0.75 n)))
        iqr (- q3 q1)]
    {:lower (- q1 (* 1.5 iqr))
     :upper (+ q3 (* 1.5 iqr))}))

(defn clip-outliers
  "Clip :x values using pre-computed threshold bounds."
  [ds threshold]
  (let [{:keys [lower upper]} threshold]
    (tc/add-column ds :x (-> (:x ds) (tcc/max lower) (tcc/min upper)))))

(defn train-model
  "Train a model on a prepared dataset."
  [train-ds model-spec]
  (ml/train train-ds model-spec))

(defn predict-model
  "Predict on test data using a trained model."
  [test-ds model]
  (ml/predict test-ds model))

;; ## Model specifications

(def cart-spec
  {:model-type :scicloj.ml.tribuo/regression
   :tribuo-components [{:name "cart"
                        :type "org.tribuo.regression.rtree.CARTRegressionTrainer"
                        :properties {:maxDepth "8"}}]
   :tribuo-trainer-name "cart"})

(def linear-sgd-spec
  {:model-type :scicloj.ml.tribuo/regression
   :tribuo-components [{:name "squared"
                        :type "org.tribuo.regression.sgd.objectives.SquaredLoss"}
                       {:name "trainer"
                        :type "org.tribuo.regression.sgd.linear.LinearSGDTrainer"
                        :properties {:objective "squared"
                                     :epochs "50"
                                     :loggingInterval "10000"}}]
   :tribuo-trainer-name "trainer"})

;; ---

;; ## Caching-fn wrappers
;;
;; Each pipeline function gets a `caching-fn` wrapper with an
;; appropriate storage policy:
;;
;; - **`:mem`** for cheap shared steps (threshold, clipping, features,
;;   prediction) — no disk I/O, but in-memory dedup ensures each
;;   runs once
;; - **`:mem+disk`** (default) for expensive steps (model training)
;;   — persists to disk, survives JVM restarts
;; - **`:none`** for steps where we only want DAG tracking without
;;   any shared caching

(def c-fit-outlier-threshold
  (pocket/caching-fn #'fit-outlier-threshold {:storage :mem}))

(def c-clip-outliers
  (pocket/caching-fn #'clip-outliers {:storage :mem}))

(def c-prepare-features
  (pocket/caching-fn #'prepare-features {:storage :mem}))

(def c-train-model
  (pocket/caching-fn #'train-model))

(def c-predict-model
  (pocket/caching-fn #'predict-model {:storage :mem}))

;; ---

;; ## Custom pipeline steps
;;
;; metamorph provides the pipeline machinery we need:
;; `mm/pipeline` composes steps, `mm/lift` wraps stateless functions,
;; `mm/fit-pipe` and `mm/transform-pipe` run pipelines in each mode.
;; These work with `Cached` references in `:metamorph/data` — they pass the
;; context through without inspecting the data.
;;
;; We only need two custom step types that metamorph does not provide:

;; ### `pocket-fitted`
;;
;; Creates a stateful pipeline step from two functions: one that fits
;; parameters on training data, and one that applies those parameters
;; to any dataset. In `:fit` mode, both are called. In `:transform`
;; mode, only the apply function runs, using the parameters saved
;; during `:fit`.
;;
;; This has no direct metamorph equivalent — in metamorph, each
;; stateful step must implement `(case mode :fit ... :transform ...)`
;; manually. `pocket-fitted` eliminates that boilerplate.

(defn pocket-fitted
  "Create a stateful pipeline step from fit and apply functions.
  In :fit mode, fits parameters from data and applies them.
  In :transform mode, applies previously fitted parameters."
  [fit-caching-fn apply-caching-fn]
  (fn [{:metamorph/keys [data mode id] :as ctx}]
    (case mode
      :fit (let [fitted (fit-caching-fn data)]
             (-> ctx (assoc id fitted) (assoc :metamorph/data (apply-caching-fn data fitted))))
      :transform (assoc ctx :metamorph/data (apply-caching-fn data (get ctx id))))))

;; ### `pocket-model`
;;
;; The model step — like `ml/model`. Trains in `:fit` mode (cached via
;; Pocket) and stores the model under its step ID. In `:transform`
;; mode, predicts using the stored model and saves the preprocessed
;; test data as `:target` (needed for metric computation in
;; `pocket-evaluate-pipelines`).

(defn pocket-model
  "Create a model pipeline step. Trains in :fit mode,
  predicts in :transform mode. Like ml/model."
  [model-spec]
  (fn [{:metamorph/keys [data mode id] :as ctx}]
    (case mode
      :fit (assoc ctx id (c-train-model data model-spec))
      :transform (let [model (get ctx id)]
                   (assoc ctx :target data
                          :metamorph/data (c-predict-model data model))))))

;; ---

;; ## Composing a pipeline
;;
;; With these tools, we can build a specific pipeline by composing
;; steps — the same way we'd use `mm/pipeline` in metamorph:

(def data-c
  (pocket/cached #'make-regression-data
                 {:f #'nonlinear-fn :n 500 :noise-sd 0.5 :seed 42
                  :outlier-fraction 0.1 :outlier-scale 15}))

(def split-c (pocket/cached #'split-dataset data-c {:seed 42}))
(def train-c (pocket/cached :train split-c))
(def test-c (pocket/cached :test split-c))

(def pipe-cart
  (mm/pipeline
   {:metamorph/id :clip} (pocket-fitted c-fit-outlier-threshold c-clip-outliers)
   {:metamorph/id :prep} (mm/lift c-prepare-features :poly+trig)
   {:metamorph/id :model} (pocket-model cart-spec)))

;; Fit on training data:

(def fit-ctx (mm/fit-pipe train-c pipe-cart))

;; Transform on test data (using fitted params from training):

(def transform-ctx (mm/transform-pipe test-c pipe-cart fit-ctx))

;; The fitted context carries the model and threshold as `Cached` references:

(kind/pprint @(:model fit-ctx))

(kind/test-last
 [(fn [model] (map? model))])

;; Predictions:

(tc/head (deref (:metamorph/data transform-ctx)))

;; Compute RMSE from the target (preprocessed test data) and predictions:

(loss/rmse (:y @(:target transform-ctx))
           (:y @(:metamorph/data transform-ctx)))

(kind/test-last
 [(fn [rmse] (< rmse 5.0))])

;; ### Provenance
;;
;; The model `Cached` reference carries full provenance — from the trained
;; model back through clipping, feature engineering, and data
;; generation to the original scalar parameters:

(pocket/origin-story-mermaid (:model fit-ctx))

(pocket/cleanup!)

;; ---

;; ## Splits as Cached references
;;
;; For cross-validation, we need k train/test splits — each as a
;; `Cached` reference so the full provenance chain is maintained.

(defn nth-split-train
  "Extract the train set of the nth split."
  [ds split-method split-params idx]
  (:train (nth (tc/split->seq ds split-method split-params) idx)))

(defn nth-split-test
  "Extract the test set of the nth split."
  [ds split-method split-params idx]
  (:test (nth (tc/split->seq ds split-method split-params) idx)))

(defn- n-splits
  "Derive the number of splits from the method and params,
  without materializing the dataset."
  [split-method split-params]
  (case split-method
    :kfold (:k split-params 5)
    :holdout 1
    :bootstrap (:repeats split-params 1)
    :loo (throw (ex-info "pocket-splits does not support :loo (needs dataset size)" {}))))

(defn pocket-splits
  "Create k-fold splits as Cached references.
  Returns [{:train Cached, :test Cached, :idx int} ...]."
  [data-c split-method split-params]
  (vec (for [idx (range (n-splits split-method split-params))]
         {:train (pocket/cached #'nth-split-train
                                data-c split-method split-params idx)
          :test (pocket/cached #'nth-split-test
                               data-c split-method split-params idx)
          :idx idx})))

;; ---

;; ## Evaluation loop
;;
;; `pocket-evaluate-pipelines` runs pipelines across splits — like
;; metamorph.ml's `evaluate-pipelines`. For each pipeline × each
;; fold, it fits on train, transforms on test, and computes a metric.

(defn pocket-evaluate-pipelines
  "Evaluate pipelines across k-fold splits.
  Like ml/evaluate-pipelines, but built on Cached references.
  
  `pipelines` is a seq of pipeline functions (from mm/pipeline).
  `metric-fn` takes (test-ds, prediction-ds) and returns a number."
  [data-c split-method split-params pipelines metric-fn]
  (let [splits (pocket-splits data-c split-method split-params)]
    (vec (for [pipe-fn pipelines
               {:keys [train test idx]} splits]
           (let [fit-ctx (mm/fit-pipe train pipe-fn)
                 transform-ctx (mm/transform-pipe test pipe-fn fit-ctx)
                 metric (metric-fn @(:target transform-ctx)
                                   @(:metamorph/data transform-ctx))]
             {:split-idx idx
              :metric metric
              :model (:model fit-ctx)})))))

;; ---

;; ## Demo: cross-validation
;;
;; 3-fold CV with three pipeline configurations:

(defn rmse-metric
  "Compute RMSE between actual and predicted :y columns."
  [test-ds pred-ds]
  (loss/rmse (:y test-ds) (:y pred-ds)))

(defn make-pipe [{:keys [feature-set model-spec]}]
  (mm/pipeline
   {:metamorph/id :clip} (pocket-fitted c-fit-outlier-threshold c-clip-outliers)
   {:metamorph/id :prep} (mm/lift c-prepare-features feature-set)
   {:metamorph/id :model} (pocket-model model-spec)))

(def configs
  [{:feature-set :poly+trig :model-spec cart-spec}
   {:feature-set :raw :model-spec cart-spec}
   {:feature-set :poly+trig :model-spec linear-sgd-spec}])

(def results
  (pocket-evaluate-pipelines data-c :kfold {:k 3 :seed 42}
                             (mapv make-pipe configs)
                             rmse-metric))

;; 3 configs × 3 folds = 9 evaluations:

(count results)

(kind/test-last
 [(fn [n] (= n 9))])

;; Aggregate mean RMSE per config:

(def summary
  (let [grouped (partition 3 results)]
    (mapv (fn [config rs]
            {:feature-set (:feature-set config)
             :model-type (-> config :model-spec :tribuo-trainer-name)
             :mean-rmse (tcc/mean (map :metric rs))})
          configs grouped)))

(tc/dataset summary)

(kind/test-last
 [(fn [ds] (= 3 (tc/row-count ds)))])

;; Second run — all training hits cache:

(def results-2
  (pocket-evaluate-pipelines data-c :kfold {:k 3 :seed 42}
                             (mapv make-pipe configs)
                             rmse-metric))

(= (mapv :metric results) (mapv :metric results-2))

(kind/test-last
 [(fn [eq] (true? eq))])

;; ---

;; ## Demo: hyperparameter sweep
;;
;; Vary tree depth × feature set. Each unique combination trains once
;; and is cached. Re-running adds only new combinations.

(pocket/cleanup!)

(def sweep-configs
  (vec (for [depth [4 6 8 12]
             fs [:raw :poly+trig]]
         {:feature-set fs
          :model-spec {:model-type :scicloj.ml.tribuo/regression
                       :tribuo-components [{:name "cart"
                                            :type "org.tribuo.regression.rtree.CARTRegressionTrainer"
                                            :properties {:maxDepth (str depth)}}]
                       :tribuo-trainer-name "cart"}})))

(def sweep-results
  (pocket-evaluate-pipelines data-c :kfold {:k 3 :seed 42}
                             (mapv make-pipe sweep-configs)
                             rmse-metric))

;; 8 configs × 3 folds = 24 evaluations:

(count sweep-results)

(kind/test-last
 [(fn [n] (= n 24))])

;; Results by depth and feature set:

(def sweep-summary
  (let [grouped (partition 3 sweep-results)]
    (->> (mapv (fn [config rs]
                 {:depth (-> config :model-spec :tribuo-components
                             first :properties :maxDepth)
                  :feature-set (:feature-set config)
                  :mean-rmse (tcc/mean (map :metric rs))})
               sweep-configs grouped)
         (sort-by :mean-rmse))))

(tc/dataset sweep-summary)

(kind/test-last
 [(fn [ds] (= 8 (tc/row-count ds)))])

;; On this synthetic data, the shallow tree (depth 4) with raw features
;; outperforms deeper trees and engineered features — a reminder that
;; more complexity does not always help.

;; ---

;; ## Provenance
;;
;; Pick one result and trace its full provenance. The DAG goes
;; from the trained model back to the original scalar parameters
;; (seed, noise-sd, outlier-fraction, etc.).

(pocket/origin-story-mermaid (:model (first sweep-results)))

;; ---

;; ## Discussion
;;
;; **What the Pocket DAG approach brings to an ML workflow:**
;;
;; | Aspect | What Pocket adds |
;; |--------|-----------------|
;; | Caching | Per-step, configurable — each step chooses `:mem`, `:mem+disk`, or `:none` |
;; | Provenance | Full DAG via `origin-story` — trace any result to its parameters |
;; | Disk persistence | Cached models and intermediates survive JVM restarts |
;; | Concurrent dedup | `ConcurrentHashMap` ensures each computation runs once across threads |
;;
;; **Reusing metamorph:**
;;
;; We use `mm/pipeline`, `mm/lift`, `mm/fit-pipe`, and
;; `mm/transform-pipe` directly — they pass `Cached` references through
;; `:metamorph/data` without inspecting them. Pocket only adds two
;; custom step types:
;;
;; - **`pocket-model`** — like `ml/model`, but caches training via
;;   `pocket/cached` so models persist to disk
;; - **`pocket-fitted`** — a new pattern for stateful steps
;;
;; In metamorph, every stateful step must dispatch on mode manually:
;;
;; ```clj
;; ;; metamorph style — manual mode dispatch
;; (fn [{:metamorph/keys [data mode id] :as ctx}]
;;   (case mode
;;     :fit       (let [bounds (fit-threshold data)]
;;                  (assoc ctx id bounds
;;                         :metamorph/data (clip data bounds)))
;;     :transform (let [bounds (get ctx id)]
;;                  (assoc ctx :metamorph/data (clip data bounds)))))
;; ```
;;
;; `pocket-fitted` eliminates that boilerplate — we just provide
;; the fit function and the apply function:
;;
;; ```clj
;; ;; Pocket style — same behavior, less ceremony
;; (pocket-fitted c-fit-outlier-threshold c-clip-outliers)
;; ```
;;
;; On top of this, `pocket-evaluate-pipelines` and `pocket-splits`
;; wrap cross-validation in `Cached` references for provenance. Everything
;; else is standard metamorph.
;;
;; **What we write:**
;;
;; 1. Plain pipeline functions (data in, data out)
;; 2. `caching-fn` wrappers with storage policies (one line each)
;; 3. Pipeline composition via `mm/pipeline` with our custom steps
;; 4. `pocket-evaluate-pipelines` for cross-validation across `Cached` splits
;;
;; **Open question: where should the custom steps live?**
;; `pocket-fitted`, `pocket-model`, `pocket-splits`, and
;; `pocket-evaluate-pipelines` are currently defined in this notebook.
;; A future `scicloj.pocket.ml` namespace could provide them — but
;; only if the pattern proves stable across different use cases.

;; ## Cleanup

(pocket/cleanup!)
