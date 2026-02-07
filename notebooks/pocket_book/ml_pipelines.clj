;; # Example: ML Pipelines with Pocket
;;
;; The [previous chapter](ml_workflows.html) showed how Pocket caches
;; individual steps of an ML workflow — data generation, splitting,
;; feature engineering, and model training — as plain Clojure functions.
;; That approach works well when you control the pipeline yourself.
;;
;; But what if you want **cross-validation**, **hyperparameter search**,
;; or a **unified pipeline** that bundles preprocessing and model into
;; one callable? That's what
;; [metamorph.ml](https://github.com/scicloj/metamorph.ml) provides.
;;
;; This chapter shows how to combine the two:
;;
;; 1. **The regression problem** — a quick recap of the data and models
;; 2. **Metamorph.ml pipelines** — rewriting the workflow as a pipeline
;; 3. **Metamorph.ml's built-in cache** — what it offers
;; 4. **Pocket-caching models** — a `pocket-model` step that caches
;;    training through Pocket
;; 5. **Cross-validation and hyperparameter search** — where caching
;;    pays off: many pipelines × many splits, all cached
;; 6. **Scaling comparison** — how caching time changes with data size
;;
;; **Who this is for**: Clojure developers exploring ML pipelines.
;; Some familiarity with ML concepts (train/test splits, cross-validation,
;; RMSE) is helpful but not essential — the
;; [previous chapter](ml_workflows.html) introduces them gently.

;; ## Setup

(ns pocket-book.ml-pipelines
  (:require
   ;; Logging setup for this chapter (see Logging chapter):
   [pocket-book.logging]
   ;; Pocket API:
   [scicloj.pocket :as pocket]
   ;; Annotating kinds of visualizations:
   [scicloj.kindly.v4.kind :as kind]
   ;; Data processing:
   [tablecloth.api :as tc]
   [tablecloth.column.api :as tcc]
   [tech.v3.datatype :as dtype]
   [tech.v3.dataset :as ds]
   [tech.v3.dataset.modelling :as ds-mod]
   [tech.v3.dataset.column-filters :as cf]
   ;; Machine learning:
   [scicloj.metamorph.core :as mm]
   [scicloj.metamorph.ml :as ml]
   [scicloj.metamorph.ml.loss :as loss]
   [scicloj.ml.tribuo]))

(def cache-dir "/tmp/pocket-ml-pipelines")

(pocket/set-base-cache-dir! cache-dir)

(pocket/cleanup!)

;; ---

;; ## Section 1 — The regression problem (recap)

;; We reuse the scenario from the
;; [ML Workflows](ml_workflows.html) chapter: predict $y = \sin(x) \cdot x$
;; from synthetic data. Two model types — a linear model that needs
;; hand-crafted features, and a decision tree that discovers structure
;; on its own. The details are in that chapter; here we just set up
;; the building blocks.

;; ### Data and feature functions

(defn make-regression-data
  "Generate a synthetic regression dataset.
  `f` is a function from x to y (the ground truth).
  Returns a dataset with columns `:x` and `:y`."
  [{:keys [f n noise-sd seed]}]
  (let [rng (java.util.Random. (long seed))
        xs (vec (repeatedly n #(* 10.0 (.nextDouble rng))))
        ys (mapv (fn [x] (+ (double (f x))
                            (* (double noise-sd) (.nextGaussian rng))))
                 xs)]
    (-> (tc/dataset {:x xs :y ys})
        (ds-mod/set-inference-target :y))))

(defn nonlinear-fn
  "y = sin(x) · x — our ground truth."
  [x]
  (* (Math/sin x) x))

(defn prepare-features
  "Add derived columns based on `feature-set`:
  `:raw` (no extras), `:poly+trig` (x², sin(x), cos(x))."
  [ds feature-set]
  (let [x (:x ds)]
    (-> (case feature-set
          :raw ds
          :poly+trig (tc/add-columns ds {:x2 (tcc/sq x)
                                         :sin-x (tcc/sin x)
                                         :cos-x (tcc/cos x)}))
        (ds-mod/set-inference-target :y))))

;; ### Model specifications

(def linear-sgd-spec
  "Linear regression via stochastic gradient descent."
  {:model-type :scicloj.ml.tribuo/regression
   :tribuo-components [{:name "squared"
                        :type "org.tribuo.regression.sgd.objectives.SquaredLoss"}
                       {:name "trainer"
                        :type "org.tribuo.regression.sgd.linear.LinearSGDTrainer"
                        :properties {:objective "squared"
                                     :epochs "50"
                                     :loggingInterval "10000"}}]
   :tribuo-trainer-name "trainer"})

(def cart-spec
  "CART regression tree with max depth 8."
  {:model-type :scicloj.ml.tribuo/regression
   :tribuo-components [{:name "cart"
                        :type "org.tribuo.regression.rtree.CARTRegressionTrainer"
                        :properties {:maxDepth "8"}}]
   :tribuo-trainer-name "cart"})

;; ### Quick check: train and evaluate directly

(def ds-500 (make-regression-data {:f nonlinear-fn :n 500 :noise-sd 0.5 :seed 42}))

(def splits (first (tc/split->seq ds-500 :holdout {:seed 42})))

(let [train-prep (prepare-features (:train splits) :poly+trig)
      test-prep (prepare-features (:test splits) :poly+trig)
      model (ml/train train-prep cart-spec)]
  {:rmse (loss/rmse (:y test-prep) (:y (ml/predict test-prep model)))})

(kind/test-last
 [(fn [m] (< (:rmse m) 2.0))])

;; With 500 data points and a CART tree, RMSE is well under 2.
;; Now let's see how metamorph.ml pipelines can formalize this.

;; ---

;; ## Section 2 — Metamorph.ml pipelines

;; A metamorph
;; [pipeline](https://github.com/scicloj/metamorph.ml#extracting-results)
;; bundles preprocessing and model
;; into a single callable. Instead of calling `prepare-features`, then
;; `ml/train`, then `ml/predict` by hand, you compose those steps once
;; and let the framework run them in two modes:
;;
;; - **`:fit`** — processes training data, learning any parameters
;;   (e.g. model weights)
;; - **`:transform`** — applies the learned parameters to new data
;;   (e.g. making predictions)
;;
;; The pipeline passes a **context map** between steps. The map always
;; has `:metamorph/data` (the current dataset) and `:metamorph/mode`
;; (`:fit` or `:transform`). Each step reads the data, does its work,
;; and puts the result back.

;; ### A first pipeline

;; `mm/lift` turns a plain `(dataset, args...) → dataset` function
;; into a pipeline step that operates on `:metamorph/data`.
;; `ml/model` is the step that trains in `:fit` and predicts in `:transform`.

(def cart-pipeline
  (mm/pipeline
   (mm/lift prepare-features :poly+trig)
   {:metamorph/id :model} (ml/model cart-spec)))

;; The `{:metamorph/id :model}` tag gives the model step a fixed name.
;; This is required so `evaluate-pipelines` (Section 5) can find the
;; trained model in the context.

;; ### Fit and transform

(def fitted-ctx
  (mm/fit-pipe (:train splits) cart-pipeline))

;; The fitted context now contains the trained model under the `:model` key.
;; We can apply it to test data:

(def predictions
  (:metamorph/data
   (mm/transform-pipe (:test splits) cart-pipeline fitted-ctx)))

(loss/rmse (:y (:test splits)) (:y predictions))

(kind/test-last
 [(fn [rmse] (< rmse 2.0))])

;; Same result as before, but the pipeline is now a single object
;; that can be passed to evaluation functions.

;; ### Multiple pipelines

;; We can define several pipeline variants and compare them.
;; Different feature sets, different models — each is a pipeline:

(def pipe-fns
  {:cart-raw (mm/pipeline
              (mm/lift prepare-features :raw)
              {:metamorph/id :model} (ml/model cart-spec))
   :cart-poly (mm/pipeline
               (mm/lift prepare-features :poly+trig)
               {:metamorph/id :model} (ml/model cart-spec))
   :sgd-poly (mm/pipeline
              (mm/lift prepare-features :poly+trig)
              {:metamorph/id :model} (ml/model linear-sgd-spec))})

(def manual-results
  (into {}
        (for [[pipe-name pipe-fn] pipe-fns]
          (let [fitted (mm/fit-pipe (:train splits) pipe-fn)
                pred-ds (:metamorph/data
                         (mm/transform-pipe (:test splits) pipe-fn fitted))]
            [pipe-name
             {:rmse (loss/rmse (:y (:test splits)) (:y pred-ds))}]))))

manual-results

(kind/test-last
 [(fn [m] (< (:rmse (:cart-raw m)) 2.0))])

;; This works, but we wrote the fit-transform loop ourselves.
;; We also used a single train/test split — not very robust.
;; To do cross-validation or compare many pipelines systematically,
;; we'd need more boilerplate. That's what `evaluate-pipelines` does.

;; ### `evaluate-pipelines` — systematic comparison

;; `evaluate-pipelines` runs each pipeline through every train/test
;; split, measures a metric, and returns structured results.
;; We use `tc/split->seq` to create splits:

(def holdout-splits
  (tc/split->seq ds-500 :holdout {:seed 42}))

;; Each split is a map with `:train` and `:test` datasets.

(def eval-results
  (ml/evaluate-pipelines
   (vals pipe-fns)
   holdout-splits
   loss/rmse
   :loss
   {:return-best-pipeline-only false
    :return-best-crossvalidation-only false}))

;; Extract metrics:

(mapv (fn [pipe-results]
        (let [r (first pipe-results)]
          {:rmse (get-in r [:test-transform :metric])
           :fit-ms (:timing-fit r)}))
      eval-results)

(kind/test-last
 [(fn [rows] (every? #(number? (:rmse %)) rows))])

;; Each result includes the test metric, timing, and the full fitted
;; context. But notice: **every call recomputes everything from
;; scratch**. If a pipeline takes minutes to train, running it
;; twice wastes time. Let's look at caching options.

;; ---

;; ## Section 3 — Metamorph.ml's built-in cache

;; metamorph.ml has a built-in caching mechanism via the
;; `train-predict-cache` atom. It stores trained models in memory,
;; keyed by `(hash dataset)` + `(hash options)`:

ml/train-predict-cache

;; By default, caching is off (`{:use-cache false}`). Let's enable it
;; with a simple in-memory store:

(def mm-cache (atom {}))

(reset! ml/train-predict-cache
        {:use-cache true
         :get-fn (fn [k] (get @mm-cache k))
         :set-fn (fn [k v] (swap! mm-cache assoc k v))})

;; Now `ml/train` checks this cache before training:

(let [train-prep (prepare-features (:train splits) :poly+trig)]
  (let [start (System/nanoTime)
        _ (ml/train train-prep cart-spec)
        first-ms (/ (- (System/nanoTime) start) 1e6)

        start2 (System/nanoTime)
        _ (ml/train train-prep cart-spec)
        second-ms (/ (- (System/nanoTime) start2) 1e6)]
    {:first-ms (Math/round first-ms)
     :second-ms (Math/round second-ms)
     :cache-entries (count @mm-cache)}))

(kind/test-last
 [(fn [m] (and (= 1 (:cache-entries m))
               (> (:first-ms m) (:second-ms m))))])

;; The second call is nearly instant — it returns the cached model.
;; This is handy for quick in-session iteration: zero configuration,
;; and the cache sits right inside metamorph.ml.
;;
;; For longer-running workflows — where you restart the REPL between
;; sessions, or want to inspect what's been cached — Pocket can add
;; a few things on top. Let's see how.

(reset! ml/train-predict-cache
        {:use-cache false
         :get-fn (fn [k] nil)
         :set-fn (fn [k v] nil)})

;; ---

;; ## Section 4 — Pocket-caching models

;; Pocket adds a few capabilities on top of the built-in cache:
;; **disk persistence** (cached models survive JVM restarts),
;; **identity-based keys** (SHA-1 hashes for reliable lookups),
;; **provenance** (you can inspect what each cache entry represents),
;; and **concurrency deduplication** (concurrent calls to the same
;; computation run it only once and share the result).
;;
;; The idea: create a pipeline step that behaves exactly like
;; `ml/model`, but wraps the `ml/train` call with `pocket/cached`.
;; This is a drop-in replacement — swap `ml/model` for `pocket-model`
;; in any pipeline, and training results are cached to disk.

;; ### The `pocket-model` step

(defn pocket-model
  "Like `ml/model`, but caches `ml/train` calls through Pocket.
   Drop-in replacement for `ml/model` in metamorph pipelines.

   In `:fit` mode, wraps `ml/train` with `pocket/cached` — the trained
   model is persisted to disk, keyed by the dataset content and options.
   In `:transform` mode, calls `ml/predict` directly (predictions are
   cheap and dataset-dependent, so caching them is usually not worth it)."
  [options]
  (fn [{:metamorph/keys [id data mode] :as ctx}]
    (case mode
      :fit
      (let [model (deref (pocket/cached #'ml/train data options))]
        (assoc ctx id (assoc model ::ml/unsupervised? false)))
      :transform
      (-> ctx
          (update id assoc
                  ::ml/feature-ds (cf/feature data)
                  ::ml/target-ds (cf/target data))
          (assoc :metamorph/data (ml/predict data (get ctx id)))))))

;; A few things to note:
;;
;; - `pocket/cached` takes `#'ml/train` (a var) plus the dataset and
;;   options as arguments. The cache key is derived from the dataset's
;;   string representation (SHA-1 hashed for long keys) and the options map.
;; - The `::ml/unsupervised?` flag tells `evaluate-pipelines` this is
;;   a supervised model.
;; - In `:transform` mode, we store `::ml/feature-ds` and `::ml/target-ds`
;;   in the context — `evaluate-pipelines` reads these to compute metrics.

;; ### Using `pocket-model` in a pipeline

(pocket/cleanup!)

(def pocket-cart-pipe
  (mm/pipeline
   (mm/lift prepare-features :poly+trig)
   {:metamorph/id :model} (pocket-model cart-spec)))

;; First run — trains and caches:

(def pocket-fitted (mm/fit-pipe (:train splits) pocket-cart-pipe))

;; The model is now cached on disk. Let's verify:

(pocket/cache-stats)

(kind/test-last
 [(fn [stats] (= 1 (:total-entries stats)))])

;; Second run — loads from cache (no "Cache miss" log message):

(def pocket-fitted-2 (mm/fit-pipe (:train splits) pocket-cart-pipe))

;; Both produce the same predictions:

(let [pred1 (:metamorph/data (mm/transform-pipe (:test splits) pocket-cart-pipe pocket-fitted))
      pred2 (:metamorph/data (mm/transform-pipe (:test splits) pocket-cart-pipe pocket-fitted-2))]
  {:rmse-1 (loss/rmse (:y (:test splits)) (:y pred1))
   :rmse-2 (loss/rmse (:y (:test splits)) (:y pred2))})

(kind/test-last
 [(fn [m] (= (:rmse-1 m) (:rmse-2 m)))])

;; Since Pocket persists to disk, this cache **survives JVM restarts**.
;; Close the REPL, reopen it, and the cached model is still there —
;; loaded from disk in milliseconds.

;; ### `pocket-model` works with `evaluate-pipelines`

;; Since `pocket-model` follows the same protocol as `ml/model`,
;; it works seamlessly with `evaluate-pipelines`:

(pocket/cleanup!)

(def pocket-eval
  (ml/evaluate-pipelines
   [pocket-cart-pipe]
   holdout-splits
   loss/rmse
   :loss
   {:return-best-pipeline-only false
    :return-best-crossvalidation-only false}))

(let [r (first (first pocket-eval))]
  {:test-rmse (get-in r [:test-transform :metric])})

(kind/test-last
 [(fn [m] (< (:test-rmse m) 2.0))])

;; ---

;; ## Section 5 — The payoff: cross-validation and hyperparameter search

;; The real benefit of caching inside `evaluate-pipelines` appears when
;; you run **many pipelines** over **many splits**. Each unique
;; (dataset, options) combination trains once and is cached. Re-running
;; the evaluation — after tweaking one pipeline, or simply re-running
;; the notebook — reuses everything that hasn't changed.

;; ### Hyperparameter search over tree depth

;; A decision tree's `maxDepth` controls how complex its splits can be.
;; Too shallow and it underfits; too deep and it overfits. Let's search
;; over several values:

(pocket/cleanup!)

(def depth-values [2 4 6 8 12])

(def depth-pipe-fns
  (vec (for [depth depth-values]
         (let [spec {:model-type :scicloj.ml.tribuo/regression
                     :tribuo-components [{:name "cart"
                                          :type "org.tribuo.regression.rtree.CARTRegressionTrainer"
                                          :properties {:maxDepth (str depth)}}]
                     :tribuo-trainer-name "cart"}]
           (mm/pipeline
            (mm/lift prepare-features :poly+trig)
            {:metamorph/id :model} (pocket-model spec))))))

;; ### 3-fold cross-validation

;; Instead of a single holdout split, we use 3-fold cross-validation:
;; the data is split into 3 parts, and each part takes a turn as the
;; test set. This gives 3 estimates per pipeline, reducing the risk of
;; a lucky or unlucky split.

(def kfold-splits
  (tc/split->seq ds-500 :kfold {:k 3 :seed 42}))

;; 5 depths × 3 folds = 15 training runs. First run:

(defn run-depth-search []
  (ml/evaluate-pipelines
   depth-pipe-fns
   kfold-splits
   loss/rmse
   :loss
   {:return-best-pipeline-only false
    :return-best-crossvalidation-only true}))

(def first-run-ms
  (let [start (System/nanoTime)
        _ (run-depth-search)
        elapsed (/ (- (System/nanoTime) start) 1e6)]
    (Math/round elapsed)))

first-run-ms

;; Second run — all 15 training calls hit cache:

(def second-run-ms
  (let [start (System/nanoTime)
        _ (run-depth-search)
        elapsed (/ (- (System/nanoTime) start) 1e6)]
    (Math/round elapsed)))

second-run-ms

(kind/test-last
 [(fn [ms] (< ms first-run-ms))])

;; The cached run is much faster — no model training, just cache lookups.

(pocket/cache-stats)

(kind/test-last
 [(fn [stats] (= 15 (:total-entries stats)))])

;; ### Results by depth

(def depth-results (run-depth-search))

(def depth-summary
  (mapv (fn [pipe-results depth]
          {:depth depth
           :test-rmse (get-in (first pipe-results)
                              [:test-transform :metric])})
        depth-results
        depth-values))

depth-summary

(kind/test-last
 [(fn [rows] (= (count rows) (count depth-values)))])

;; ### Combined search: depth × feature set × model type

;; Let's go bigger. We'll search over:
;; - 3 tree depths (4, 6, 8)
;; - 2 feature sets (`:raw`, `:poly+trig`)
;; - 2 model types (CART tree, linear SGD — SGD only with `:poly+trig`)
;;
;; That's 3×2 + 3×1 = 9 pipelines, each evaluated with 3-fold CV = 27 training runs.

(pocket/cleanup!)

(def search-pipe-fns
  (vec
   (concat
     ;; CART × depths × feature sets
    (for [depth [4 6 8]
          fs [:raw :poly+trig]]
      (let [spec {:model-type :scicloj.ml.tribuo/regression
                  :tribuo-components [{:name "cart"
                                       :type "org.tribuo.regression.rtree.CARTRegressionTrainer"
                                       :properties {:maxDepth (str depth)}}]
                  :tribuo-trainer-name "cart"}]
        (mm/pipeline
         (mm/lift prepare-features fs)
         {:metamorph/id :model} (pocket-model spec))))
     ;; Linear SGD × depths (only with poly+trig — raw features can't
     ;; capture the nonlinear target)
    (for [_depth [4 6 8]]
      (mm/pipeline
       (mm/lift prepare-features :poly+trig)
       {:metamorph/id :model} (pocket-model linear-sgd-spec))))))

(def search-results
  (ml/evaluate-pipelines
   search-pipe-fns
   kfold-splits
   loss/rmse
   :loss
   {:return-best-pipeline-only false
    :return-best-crossvalidation-only true}))

;; Best result:

(let [best (first (first
                   (sort-by #(get-in (first %) [:test-transform :metric])
                            search-results)))]
  {:best-rmse (get-in best [:test-transform :metric])
   :best-fit-ms (:timing-fit best)})

(kind/test-last
 [(fn [m] (< (:best-rmse m) 2.0))])

;; All 27 training runs are now cached. Re-running the search is instant.
;; If we add a new depth value or feature set, only the new combinations
;; train — everything else comes from cache.

(pocket/cache-stats)

;; ---

;; ## Section 6 — Does caching matter? Scaling to realistic sizes

;; With 500 data points, training is fast and caching overhead is
;; comparable to the training time itself. But real datasets are bigger.
;; Let's see how the numbers change with 500, 5,000, and 10,000 rows.
;;
;; For each size, we run a single CART pipeline through 3-fold
;; cross-validation (3 training runs) and measure:
;; - **Uncached**: using `ml/model` (no caching)
;; - **Pocket first run**: using `pocket-model` (cache miss → train + write)
;; - **Pocket second run**: using `pocket-model` (cache hit → read from disk)

(defn time-pipeline
  "Time a 3-fold CV evaluation of a single pipeline.
   Returns elapsed milliseconds."
  [pipe-fn data]
  (let [start (System/nanoTime)
        _ (ml/evaluate-pipelines
           [pipe-fn]
           (tc/split->seq data :kfold {:k 3 :seed 42})
           loss/rmse :loss
           {:return-best-pipeline-only false})
        elapsed (/ (- (System/nanoTime) start) 1e6)]
    (Math/round elapsed)))

(def scaling-results
  (vec
   (for [n [500 5000 10000]]
     (let [data (make-regression-data
                 {:f nonlinear-fn :n n :noise-sd 0.5 :seed 42})
           uncached-pipe (mm/pipeline
                          (mm/lift prepare-features :poly+trig)
                          {:metamorph/id :model} (ml/model cart-spec))
           cached-pipe (mm/pipeline
                        (mm/lift prepare-features :poly+trig)
                        {:metamorph/id :model} (pocket-model cart-spec))]
       ;; Uncached
       (pocket/cleanup!)
       (let [uncached-ms (time-pipeline uncached-pipe data)
             ;; Pocket first run (cache miss)
             first-ms (time-pipeline cached-pipe data)
             ;; Pocket second run (cache hit)
             second-ms (time-pipeline cached-pipe data)]
         {:n n
          :uncached-ms uncached-ms
          :pocket-first-ms first-ms
          :pocket-second-ms second-ms})))))

(tc/dataset scaling-results)

(kind/test-last
 [(fn [ds]
    (let [row-10k (last (tc/rows ds :as-maps))]
      (< (:pocket-second-ms row-10k)
         (:uncached-ms row-10k))))])

;; At small data sizes, the numbers are similar — training is cheap
;; regardless. But as data grows, the gap widens. At 10,000 rows,
;; the cached second run is dramatically faster than retraining.
;;
;; The real impact comes in two scenarios:
;;
;; 1. **Re-running a notebook**: Without caching, every model retrains.
;;    With Pocket, cached results load from disk in milliseconds.
;;
;; 2. **Iterating on pipelines**: Change one hyperparameter? Only that
;;    pipeline retrains. Every other combination in your search grid
;;    comes from cache.
;;
;; 3. **Concurrent pipelines**: When multiple threads train the
;;    same model, Pocket runs the computation once and shares the
;;    result — no duplicate work.
;;
;; And since Pocket persists to disk, all of this **survives JVM
;; restarts** — close the REPL, reopen, and everything is still there.

;; ### Scaling visualization

(let [rows scaling-results]
  (kind/plotly
   {:data [{:x (mapv :n rows)
            :y (mapv :uncached-ms rows)
            :mode "lines+markers"
            :name "Uncached (ml/model)"}
           {:x (mapv :n rows)
            :y (mapv :pocket-first-ms rows)
            :mode "lines+markers"
            :name "Pocket (first run)"}
           {:x (mapv :n rows)
            :y (mapv :pocket-second-ms rows)
            :mode "lines+markers"
            :name "Pocket (second run)"}]
    :layout {:xaxis {:title "Data size (n)"}
             :yaxis {:title "Time (ms)"}
             :title "3-fold CV timing by data size"}}))

;; ---

;; ## Summary

;; This chapter showed how to bring Pocket caching into metamorph.ml
;; pipelines:
;;
;; - **metamorph.ml pipelines** bundle preprocessing and model into a
;;   single callable, with `:fit` and `:transform` modes
;; - **`evaluate-pipelines`** runs pipelines through cross-validation
;;   splits and reports metrics — the standard way to compare models
;; - **`pocket-model`** is a drop-in replacement for `ml/model` that
;;   caches `ml/train` through Pocket — same pipeline structure, but
;;   training results persist to disk and survive JVM restarts
;; - **Concurrency**: when multiple threads train the same model,
;;   Pocket runs it once and shares the result
;; - **Hyperparameter search** benefits most: many pipelines × many
;;   splits create many cache entries, and re-running reuses them all
;; - **Scaling**: at realistic data sizes, cached runs are orders of
;;   magnitude faster than retraining
;;
;; For the raw Pocket approach (without metamorph.ml pipelines), see
;; the [ML Workflows](ml_workflows.html) chapter. For configuration
;; options (cache directory, storage policies), see
;; [Configuration](configuration.html).

;; ## Cleanup

(pocket/cleanup!)
