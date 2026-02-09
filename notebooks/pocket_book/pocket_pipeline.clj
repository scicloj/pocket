;; # 🚧 Draft: `pocket-pipeline` — cached ML pipelines with `evaluate-pipelines`
;;
;; The previous chapter ([pocket-model](pocket_model.html)) showed
;; how to cache model training in a
;; [metamorph.ml](https://github.com/scicloj/metamorph.ml)
;; pipeline by swapping one step. That approach is simple — just
;; replace `ml/model` with `pocket-model` — but only the training
;; step is cached through Pocket.
;;
;; This chapter explores a deeper integration: building the **entire
;; pipeline** as a chain of `pocket/caching-fn` calls, where every
;; step — data splitting, feature engineering, outlier clipping,
;; training — becomes a cached node. This gives us:
;;
;; - **Per-step storage control** — choose `:mem`, `:mem+disk`, or
;;   `:none` for each step independently
;; - **Full provenance** — `origin-story` traces any result back to
;;   the scalar parameters that produced it
;; - **Disk persistence** — cached models and intermediate results
;;   survive JVM restarts
;; - **Concurrent dedup** — same computation runs once across threads
;;
;; The key ingredient is Pocket's
;; [origin registry](cache_keys.html#origin-registry-derefed-values-keep-their-identity):
;; when a `Cached` value is derefed, the real result keeps its
;; lightweight identity. This lets us deref at each pipeline step —
;; so real datasets flow through
;; [metamorph](https://github.com/scicloj/metamorph)'s context —
;; while cache keys stay efficient. Because the data is always a
;; real dataset, we can use metamorph.ml's
;; [`evaluate-pipelines`](https://scicloj.github.io/metamorph.ml/scicloj.metamorph.ml.html#var-evaluate-pipelines)
;; directly for
;; [cross-validation](https://en.wikipedia.org/wiki/Cross-validation_(statistics))
;; and model comparison.
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
;; [cross-validation](https://en.wikipedia.org/wiki/Cross-validation_(statistics)) (`evaluate-pipelines`), [loss functions](https://en.wikipedia.org/wiki/Loss_function), and
;; [hyperparameter](https://en.wikipedia.org/wiki/Hyperparameter_(machine_learning)) search.
;;
;; ## How this chapter relates to others
;;
;; The [ML Workflows](ml_workflows.html) chapter demonstrates Pocket
;; caching with plain functions — `pocket/cached` calls wired into a
;; DAG. This chapter uses the same pipeline functions and the same
;; DAG approach, but adds **cross-validation and model comparison**
;; on top, reusing metamorph.ml's `evaluate-pipelines`.
;;
;; The [pocket-model](pocket_model.html) chapter takes the opposite
;; approach: it plugs into metamorph.ml's existing pipeline machinery
;; with a single drop-in replacement. Simpler to adopt, but only the
;; training step is cached through Pocket.
;;
;; | | pocket-model | This chapter |
;; |--|-------------|-------------|
;; | Integration effort | One-line change | Build pipeline with `caching-fn` wrappers |
;; | What's cached | Training only | Every step |
;; | Provenance | Training step only | Full DAG — any result to its parameters |
;; | Storage control | Global | Per-step |
;; | Evaluation | `ml/evaluate-pipelines` | `ml/evaluate-pipelines` (same) |

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
   ;; Column role filters (feature, target, prediction):
   [tech.v3.dataset.column-filters :as cf]
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
        xs (repeatedly n #(* 10.0 (.nextDouble rng)))
        xs-final (if (pos? outlier-fraction)
                   (let [out-rng (java.util.Random. (+ (long seed) 7919))]
                     (map (fn [x]
                            (if (< (.nextDouble out-rng) outlier-fraction)
                              (+ x (* (double outlier-scale) (.nextGaussian out-rng)))
                              x))
                          xs))
                   xs)
        ys (map (fn [x] (+ (double (f x))
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
  (let [xs (sort (:x train-ds))
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
                       {:name "linear-sgd"
                        :type "org.tribuo.regression.sgd.linear.LinearSGDTrainer"
                        :properties {:objective "squared"
                                     :epochs "50"
                                     :loggingInterval "10000"}}]
   :tribuo-trainer-name "linear-sgd"})

;; ---

;; ## Caching-fn wrappers
;;
;; Each pipeline function gets a `caching-fn` wrapper with an
;; appropriate storage policy:
;;
;; - **`:mem`** for cheap shared steps (threshold, clipping, features, prediction)
;;   — no disk I/O, but in-memory dedup ensures each runs once
;; - **`:mem+disk`** (default) for expensive steps (model training)
;;   — persists to disk, survives JVM restarts

(def c-fit-outlier-threshold
  (pocket/caching-fn #'fit-outlier-threshold {:storage :mem}))

(def c-clip-outliers
  (pocket/caching-fn #'clip-outliers {:storage :mem}))

(def c-prepare-features
  (pocket/caching-fn #'prepare-features {:storage :mem}))

(def c-predict-model
  (pocket/caching-fn #'predict-model {:storage :mem}))

;; ---

;; ## Custom pipeline steps
;;
;; metamorph provides the pipeline machinery we need:
;; `mm/pipeline` composes steps, `mm/lift` wraps stateless functions,
;; `mm/fit-pipe` and `mm/transform-pipe` run pipelines in each mode.
;;
;; We build on this with two custom step types. Each step uses
;; `caching-fn` wrappers internally and **derefs** the `Cached`
;; result, so `:metamorph/data` always holds a real dataset. The
;; [origin registry](cache_keys.html#origin-registry-derefed-values-keep-their-identity)
;; ensures these derefed datasets carry their lightweight identity,
;; so the next step's cache key stays efficient.

;; ### `pocket-fitted`
;;
;; Creates a stateful pipeline step from two functions: one that fits
;; parameters on training data, and one that applies those parameters
;; to any dataset. In `:fit` mode, both are called. In `:transform`
;; mode, only the apply function runs, using the parameters saved
;; during `:fit`.
;;
;; Both functions should be `caching-fn` wrappers. Their `Cached`
;; results are derefed before being stored in the context or in
;; `:metamorph/data`.

(defn pocket-fitted
  "Create a stateful pipeline step from fit and apply caching-fns.
  In :fit mode, fits parameters from data and applies them.
  In :transform mode, applies previously fitted parameters.
  Results are derefed so real datasets flow through the pipeline."
  [fit-caching-fn apply-caching-fn]
  (fn [{:metamorph/keys [data mode id] :as ctx}]
    (case mode
      :fit (let [fitted (deref (fit-caching-fn data))]
             (-> ctx
                 (assoc id fitted)
                 (assoc :metamorph/data (deref (apply-caching-fn data fitted)))))
      :transform (assoc ctx :metamorph/data
                        (deref (apply-caching-fn data (get ctx id)))))))

;; ### `pocket-model`
;;
;; The model step — compatible with `ml/evaluate-pipelines`. Trains
;; in `:fit` mode (cached via Pocket) and stores the model map under
;; its step ID. In `:transform` mode, predicts using the stored model
;; (also cached) and saves the preprocessed test data as `:target`
;; (for our loss computation) and as
;; `:scicloj.metamorph.ml/target-ds` (for `evaluate-pipelines`).
;;
;; The `Cached` reference from training is also stored (under
;; `:pocket/model-cached`) so we can trace provenance later.

(defn pocket-model
  "Cached model step compatible with ml/evaluate-pipelines.
  Caches training and prediction via pocket/cached. Stores the
  training Cached reference at :pocket/model-cached for provenance."
  [model-spec]
  (fn [{:metamorph/keys [data mode id] :as ctx}]
    (case mode
      :fit
      (let [model-c (pocket/cached #'ml/train data model-spec)
            model (deref model-c)]
        (assoc ctx id model
               :pocket/model-cached model-c))
      :transform
      (let [model (get ctx id)]
        (-> ctx
            (update id assoc
                    :scicloj.metamorph.ml/feature-ds (cf/feature data)
                    :scicloj.metamorph.ml/target-ds (cf/target data))
            (assoc :metamorph/data (deref (c-predict-model data model))
                   :target data))))))

;; ---

;; ## Composing a pipeline
;;
;; With these tools, we can build a pipeline by composing steps.
;; The `pocket-fitted` step handles stateful outlier clipping,
;; `mm/lift` with `(comp deref c-fn)` handles stateless cached
;; feature preparation, and `pocket-model` handles training.

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
   {:metamorph/id :prep} (mm/lift (comp deref c-prepare-features) :poly+trig)
   {:metamorph/id :model} (pocket-model cart-spec)))

;; Fit on training data. We deref the `Cached` split to get a real
;; dataset — the origin registry ensures our caching-fn wrappers
;; still see a lightweight cache key:

(def fit-ctx (mm/fit-pipe (deref train-c) pipe-cart))

;; Transform on test data (using fitted params from training):

(def transform-ctx (mm/transform-pipe (deref test-c) pipe-cart fit-ctx))

;; The fitted context carries the model:

(-> fit-ctx
    (get :model)
    (update :model-data dissoc :model-as-bytes)
    kind/pprint)

(kind/test-last
 [(fn [model] (map? model))])

;; Predictions:

(tc/head (:metamorph/data transform-ctx))

;; Compute [RMSE](https://en.wikipedia.org/wiki/Root_mean_square_deviation) from the target and predictions:

(loss/rmse (:y (get-in transform-ctx [:model :scicloj.metamorph.ml/target-ds]))
           (:y (:metamorph/data transform-ctx)))

(kind/test-last
 [(fn [rmse] (< rmse 5.0))])

;; ### Train and test loss
;;
;; A single metric on test data tells us how well the model generalizes,
;; but comparing train and test [loss](https://en.wikipedia.org/wiki/Loss_function) reveals whether the model is
;; [overfitting](https://en.wikipedia.org/wiki/Overfitting). We compute the loss separately for each, then gather
;; both into a summary.

(defn compute-loss
  "Compute RMSE between actual and predicted :y columns."
  [actual-ds predicted-ds]
  (loss/rmse (:y actual-ds) (:y predicted-ds)))

(def c-compute-loss (pocket/caching-fn #'compute-loss {:storage :mem}))

;; We already have the test predictions from `transform-ctx`. For
;; training loss, we also transform the training data through the
;; fitted pipeline:

(def train-transform-ctx (mm/transform-pipe (deref train-c) pipe-cart fit-ctx))

;; Now we compute loss on each split independently:

(def train-loss-c
  (c-compute-loss (:target train-transform-ctx)
                  (:metamorph/data train-transform-ctx)))

(def test-loss-c
  (c-compute-loss (:target transform-ctx)
                  (:metamorph/data transform-ctx)))

;; A report function gathers both into one summary:

(defn report
  "Gather train and test loss into a summary map."
  [train-loss test-loss]
  {:train-rmse train-loss
   :test-rmse test-loss})

(def c-report (pocket/caching-fn #'report {:storage :mem}))

(def summary-c (c-report train-loss-c test-loss-c))

(deref summary-c)

(kind/test-last
 [(fn [{:keys [train-rmse test-rmse]}]
    (and (< train-rmse test-rmse)
         (< test-rmse 5.0)))])

;; ### Provenance
;;
;; The summary reference carries full provenance. The origin registry
;; lets `origin-story` follow derefed values back through the caching
;; chain — so the DAG branches into train and test paths that share
;; the same model node. This diamond dependency is traced naturally:

(pocket/origin-story-mermaid summary-c)

(pocket/cleanup!)

;; ---

;; ## Splits as `Cached` references
;;
;; For cross-validation, we need k train/test splits. We create
;; them as `Cached` references — preserving provenance — and then
;; deref them to get real datasets for `ml/evaluate-pipelines`.
;; The origin registry ensures the derefed datasets carry their
;; lightweight identity.

(defn nth-split-train
  "Extract the train set of the nth split."
  [ds split-method split-params idx]
  (:train (nth (tc/split->seq ds split-method split-params) idx)))

(defn nth-split-test
  "Extract the test set of the nth split."
  [ds split-method split-params idx]
  (:test (nth (tc/split->seq ds split-method split-params) idx)))

(defn- n-splits
  "Derive the number of splits from the method and params.
  For :loo, derefs the dataset to get its row count."
  [data-c split-method split-params]
  (case split-method
    :kfold (:k split-params 5)
    :holdout 1
    :bootstrap (:repeats split-params 1)
    :loo (tc/row-count (deref data-c))))

(defn pocket-splits
  "Create k-fold splits as Cached references.
  Returns [{:train Cached, :test Cached, :idx int} ...]."
  [data-c split-method split-params]
  (for [idx (range (n-splits data-c split-method split-params))]
    {:train (pocket/cached #'nth-split-train
                           data-c split-method split-params idx)
     :test (pocket/cached #'nth-split-test
                          data-c split-method split-params idx)
     :idx idx}))

;; Create `Cached` splits and deref them for `ml/evaluate-pipelines`.
;; The derefed datasets are real (passing malli validation) while
;; carrying their origin identity (for efficient cache keys):

(def cached-splits (pocket-splits data-c :kfold {:k 3 :seed 42}))

(def splits
  (map (fn [{:keys [train test]}]
         {:train (deref train) :test (deref test)})
       cached-splits))

;; ---

;; ## Cross-validation with `ml/evaluate-pipelines`
;;
;; Because our pipeline steps deref their outputs, real datasets
;; flow through `:metamorph/data` at every point. This makes our
;; pipeline fully compatible with
;; [`evaluate-pipelines`](https://scicloj.github.io/metamorph.ml/scicloj.metamorph.ml.html#var-evaluate-pipelines),
;; which needs real datasets for metric computation.

(defn make-pipe [{:keys [feature-set model-spec]}]
  (mm/pipeline
   {:metamorph/id :clip} (pocket-fitted c-fit-outlier-threshold c-clip-outliers)
   {:metamorph/id :prep} (mm/lift (comp deref c-prepare-features) feature-set)
   {:metamorph/id :model} (pocket-model model-spec)))

(def configs
  [{:feature-set :poly+trig :model-spec cart-spec}
   {:feature-set :raw :model-spec cart-spec}
   {:feature-set :poly+trig :model-spec linear-sgd-spec}])

(def results
  (ml/evaluate-pipelines
   (map make-pipe configs)
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false
    :return-best-pipeline-only false}))

;; 3 configs × 3 folds — aggregate mean RMSE per config:

(def summary
  (map (fn [config pipeline-results]
         {:feature-set (:feature-set config)
          :model-type (-> config :model-spec :tribuo-trainer-name)
          :mean-rmse (tcc/mean (map #(-> % :test-transform :metric)
                                    pipeline-results))})
       configs results))

(tc/dataset summary)

(kind/test-last
 [(fn [ds] (= 3 (tc/row-count ds)))])

;; Second run — all training hits cache, same metrics:

(def results-2
  (ml/evaluate-pipelines
   (map make-pipe configs)
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false
    :return-best-pipeline-only false}))

(= (map #(-> % first :test-transform :metric) results)
   (map #(-> % first :test-transform :metric) results-2))

(kind/test-last
 [(fn [eq] (true? eq))])

;; ---

;; ## Hyperparameter sweep
;;
;; Vary tree depth × feature set. Each unique combination trains once
;; and is cached. Re-running adds only new combinations.

(def sweep-configs
  (for [depth [4 6 8 12]
        fs [:raw :poly+trig]]
    {:feature-set fs
     :model-spec {:model-type :scicloj.ml.tribuo/regression
                  :tribuo-components [{:name "cart"
                                       :type "org.tribuo.regression.rtree.CARTRegressionTrainer"
                                       :properties {:maxDepth (str depth)}}]
                  :tribuo-trainer-name "cart"}}))

(def sweep-results
  (ml/evaluate-pipelines
   (map make-pipe sweep-configs)
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false
    :return-best-pipeline-only false}))

;; Results by depth and feature set:

(def sweep-summary
  (->> (map (fn [config pipeline-results]
              {:depth (-> config :model-spec :tribuo-components
                          first :properties :maxDepth)
               :feature-set (:feature-set config)
               :mean-rmse (tcc/mean (map #(-> % :test-transform :metric)
                                         pipeline-results))})
            sweep-configs sweep-results)
       (sort-by :mean-rmse)))

(tc/dataset sweep-summary)

(kind/test-last
 [(fn [ds] (= 8 (tc/row-count ds)))])

;; On this synthetic data, deeper trees with engineered features
;; (`poly+trig`) perform best, while shallower trees show similar
;; results regardless of feature set.

;; ### Sweep provenance
;;
;; Pick the best result and trace its full provenance. The DAG goes
;; from the trained model back to the original scalar parameters
;; (seed, noise-sd, outlier-fraction, etc.):

(pocket/origin-story-mermaid
 (:pocket/model-cached
  (-> sweep-results first first :fit-ctx)))

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
;; `mm/transform-pipe` directly — and now also `ml/evaluate-pipelines`
;; for cross-validation and model comparison. Pocket only adds two
;; custom step types:
;;
;; - **`pocket-model`** — like `ml/model`, but caches training via
;;   `pocket/cached` so models persist to disk
;; - **`pocket-fitted`** — a general pattern for stateful steps
;;
;; **The deref-through pattern:**
;;
;; Each pipeline step wraps a `caching-fn` and immediately derefs the
;; `Cached` result. This means real datasets (not `Cached` references)
;; flow through `:metamorph/data` at every point. The
;; [origin registry](cache_keys.html#origin-registry-derefed-values-keep-their-identity)
;; provides two benefits:
;;
;; 1. **Efficient cache keys** — each derefed dataset carries its
;;    lightweight identity, so the next step's `caching-fn` avoids
;;    hashing full dataset content
;; 2. **Full provenance** — `origin-story` follows derefed values back
;;    through the registry to their `Cached` origin, preserving the
;;    complete DAG (as seen in the diamond dependency above)
;;
;; Because `:metamorph/data` is always a real dataset,
;; `ml/evaluate-pipelines` can call `cf/target`, `cf/prediction`,
;; and malli validation — things that require concrete dataset types.
;;
;; **What we write:**
;;
;; 1. Plain pipeline functions (data in, data out)
;; 2. `caching-fn` wrappers with storage policies (one line each)
;; 3. Pipeline composition via `mm/pipeline` with our custom steps
;; 4. `ml/evaluate-pipelines` for cross-validation
;;
;; **Open question: where should the custom steps live?**
;; `pocket-fitted` and `pocket-model` are currently defined in this
;; notebook. A future `scicloj.pocket.ml` namespace could provide
;; them — but only if the pattern proves stable across different
;; use cases.

;; ## Cleanup

(pocket/cleanup!)
