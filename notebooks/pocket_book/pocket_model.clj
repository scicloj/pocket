;; # 🚧 Draft: `pocket-model` — drop-in caching for metamorph.ml
;;
;; **Last modified: 2026-02-08**
;;
;; This chapter shows how to cache model training in a
;; [metamorph.ml](https://github.com/scicloj/metamorph.ml) pipeline
;; using Pocket. We define a small `pocket-model` function — a
;; drop-in replacement for `ml/model` — and use it with
;; cross-validation, grid search, and multiple model types.
;;
;; ## Background
;;
;; [metamorph.ml](https://github.com/scicloj/metamorph.ml) is the
;; Scicloj library for machine learning pipelines. It builds on
;; [metamorph](https://github.com/scicloj/metamorph), a
;; data-transformation framework where each step is a function that
;; takes a context map and returns an updated one. metamorph.ml
;; distinguishes two modes — `:fit` (learn from training data) and
;; `:transform` (apply to new data) — so a pipeline can be trained
;; once and reused for prediction.
;;
;; On top of this, metamorph.ml adds model training/prediction,
;; cross-validation (`evaluate-pipelines`), loss functions, and
;; hyperparameter search. A typical workflow looks like:
;;
;; 1. Define a pipeline of preprocessing + model steps
;; 2. Split data into folds
;; 3. Call `evaluate-pipelines` to train and score across folds
;; 4. Compare results, pick the best model
;;
;; ## Why cache with Pocket?
;;
;; metamorph.ml includes a built-in caching mechanism. This notebook
;; explores what happens when we use Pocket's caching instead,
;; bringing a few things that are natural to Pocket's design:
;;
;; - **Disk persistence** — cached models survive JVM restarts,
;;   so we can pick up where we left off across sessions
;; - **Content-based keys** — cache keys derived from function
;;   identity and full argument values via SHA-1
;; - **Concurrent dedup** — when multiple threads request the same
;;   computation, only one trains and the rest wait for the result
;;
;; The integration is lightweight: a `pocket-model` function that
;; is a drop-in replacement for `ml/model`. We swap one pipeline
;; step and everything else — `evaluate-pipelines`, preprocessing,
;; grid search — stays the same.
;;
;; **What this gives us**:
;; - Same pipeline code, same `evaluate-pipelines`
;; - Model training cached to disk (survives JVM restarts)
;; - Graceful fallback for non-serializable models

;;
;; **What this notebook does not cover**: because `pocket-model`
;; plugs into metamorph.ml's existing pipeline machinery, only the
;; model-training step is cached through Pocket. Preprocessing,
;; splitting, and evaluation happen outside Pocket's awareness —
;; there is no computational DAG tracking the full pipeline, no
;; per-step storage control (choosing whether each step caches to
;; disk, memory, or not at all), and no provenance trail that
;; connects a final metric back to the data and parameters that
;; produced it.
;; A companion notebook is in the works, exploring a deeper
;; integration where every pipeline step is a Pocket `caching-fn`,
;; giving us all of those things.

;; ## Setup

(ns pocket-book.pocket-model
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
   [tech.v3.dataset.modelling :as ds-mod]
   [tech.v3.dataset.column-filters :as cf]
   ;; Machine learning:
   [scicloj.metamorph.ml :as ml]
   [scicloj.metamorph.ml.loss :as loss]
   [scicloj.metamorph.ml.regression]
   [scicloj.metamorph.core :as mm]
   [scicloj.ml.tribuo]))

(def cache-dir "/tmp/pocket-model")

(pocket/set-base-cache-dir! cache-dir)

(pocket/cleanup!)

;; ---

;; ## The `pocket-model` function
;;
;; This is the core of the integration. It follows the same
;; contract as `ml/model` — a metamorph step that trains in
;; `:fit` mode and predicts in `:transform` mode. The only
;; difference: `ml/train` is wrapped with `pocket/cached`.
;;
;; If Nippy can't serialize a model (e.g., Apache Commons Math
;; OLS), it falls back to uncached training automatically.

(defn pocket-model
  "Drop-in replacement for ml/model that caches training via Pocket.
  Falls back to uncached training if serialization fails."
  [options]
  (fn [{:metamorph/keys [id data mode] :as ctx}]
    (case mode
      :fit
      (let [model (try
                    (deref (pocket/cached #'ml/train data options))
                    (catch Exception _e
                      (ml/train data options)))]
        (assoc ctx id (assoc model :scicloj.metamorph.ml/unsupervised?
                             (get (ml/options->model-def options)
                                  :unsupervised? false))))
      :transform
      (let [model (get ctx id)]
        (if (get model :scicloj.metamorph.ml/unsupervised?)
          ctx
          (-> ctx
              (update id assoc
                      :scicloj.metamorph.ml/feature-ds (cf/feature data)
                      :scicloj.metamorph.ml/target-ds (cf/target data))
              (assoc :metamorph/data (ml/predict data model))))))))

;; ---

;; ## Test data
;;
;; Simple synthetic regression: `y = 3x + noise`.
;; 200 rows, enough for quick feedback.

(def ds (-> (let [rng (java.util.Random. 42)]
              (tc/dataset
               {:x (vec (repeatedly 200 #(* 10.0 (.nextDouble rng))))
                :y (vec (repeatedly 200 #(+ (* 3.0 (* 10.0 (.nextDouble rng)))
                                            (* 2.0 (.nextGaussian rng)))))}))
            (ds-mod/set-inference-target :y)))

(def splits (tc/split->seq ds :kfold {:k 3 :seed 42}))

(count splits)

(kind/test-last
 [(fn [n] (= n 3))])

;; ---

;; ## Basic usage
;;
;; Use `pocket-model` in place of `ml/model`.
;; The `{:metamorph/id :model}` map step sets the step ID
;; that `evaluate-pipelines` expects.

(def cart-spec
  {:model-type :scicloj.ml.tribuo/regression
   :tribuo-components [{:name "cart"
                        :type "org.tribuo.regression.rtree.CARTRegressionTrainer"
                        :properties {:maxDepth "8"}}]
   :tribuo-trainer-name "cart"})

(def pipe-cart
  (mm/pipeline
   {:metamorph/id :model}
   (pocket-model cart-spec)))

;; First run — trains 3 models (one per fold):

(def results-1
  (ml/evaluate-pipelines
   [pipe-cart]
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false
    :return-best-pipeline-only false}))

(mapv #(-> % :test-transform :metric) (flatten results-1))

(kind/test-last
 [(fn [ms] (every? #(< % 15) ms))])

;; Cache now has 3 entries (one per fold):

(pocket/cache-stats)

(kind/test-last
 [(fn [stats] (= 3 (:total-entries stats)))])

;; Second run — all cache hits, same metrics:

(def results-2
  (ml/evaluate-pipelines
   [pipe-cart]
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false
    :return-best-pipeline-only false}))

(= (mapv #(-> % :test-transform :metric) (flatten results-1))
   (mapv #(-> % :test-transform :metric) (flatten results-2)))

(kind/test-last
 [(fn [eq] (true? eq))])

;; ---

;; ## Incremental grid search
;;
;; Start with 3 depth values, then add 3 more. Only new
;; combinations train — existing ones hit cache.

(pocket/cleanup!)

(defn cart-pipe [max-depth]
  (mm/pipeline
   {:metamorph/id :model}
   (pocket-model
    {:model-type :scicloj.ml.tribuo/regression
     :tribuo-components [{:name "cart"
                          :type "org.tribuo.regression.rtree.CARTRegressionTrainer"
                          :properties {:maxDepth (str max-depth)}}]
     :tribuo-trainer-name "cart"})))

;; Batch 1: depths 4, 8, 12

(def batch-1
  (ml/evaluate-pipelines
   (mapv cart-pipe [4 8 12])
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false
    :return-best-pipeline-only false}))

;; 3 depths × 3 folds = 9 trainings:

(pocket/cache-stats)

(kind/test-last
 [(fn [stats] (= 9 (:total-entries stats)))])

;; Batch 2: depths 4, 6, 8, 10, 12, 16
;; Depths 4, 8, 12 already cached → only 6, 10, 16 are new

(def batch-2
  (ml/evaluate-pipelines
   (mapv cart-pipe [4 6 8 10 12 16])
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false
    :return-best-pipeline-only false}))

;; 3 new depths × 3 folds = 9 new + 9 cached = 18 total:

(pocket/cache-stats)

(kind/test-last
 [(fn [stats] (= 18 (:total-entries stats)))])

;; Combine results — best depth by mean RMSE:

(let [depths [4 6 8 10 12 16]
      means (mapv (fn [pipeline-results]
                    (tcc/mean (map #(-> % :test-transform :metric) pipeline-results)))
                  batch-2)]
  (tc/dataset {:depth depths :mean-rmse means}))

(kind/test-last
 [(fn [ds] (= 6 (tc/row-count ds)))])

;; ---

;; ## Multiple model types
;;
;; Compare CART, linear SGD, and fastmath OLS in the same
;; evaluation. Each model type is cached independently.

(pocket/cleanup!)

(def sgd-spec
  {:model-type :scicloj.ml.tribuo/regression
   :tribuo-components [{:name "squared"
                        :type "org.tribuo.regression.sgd.objectives.SquaredLoss"}
                       {:name "trainer"
                        :type "org.tribuo.regression.sgd.linear.LinearSGDTrainer"
                        :properties {:objective "squared"
                                     :epochs "50"
                                     :loggingInterval "10000"}}]
   :tribuo-trainer-name "trainer"})

(def multi-results
  (ml/evaluate-pipelines
   [(mm/pipeline {:metamorph/id :model} (pocket-model cart-spec))
    (mm/pipeline {:metamorph/id :model} (pocket-model sgd-spec))
    (mm/pipeline {:metamorph/id :model} (pocket-model {:model-type :fastmath/ols}))]
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false
    :return-best-pipeline-only false}))

;; 3 model types × 3 folds = 9 entries:

(pocket/cache-stats)

(kind/test-last
 [(fn [stats] (= 9 (:total-entries stats)))])

;; Mean RMSE per model type:

(let [model-names ["CART" "SGD" "fastmath-OLS"]
      means (mapv (fn [pipeline-results]
                    (tcc/mean (map #(-> % :test-transform :metric) pipeline-results)))
                  multi-results)]
  (tc/dataset {:model model-names :mean-rmse means}))

(kind/test-last
 [(fn [ds] (= 3 (tc/row-count ds)))])

;; ---

;; ## Graceful fallback
;;
;; The built-in `metamorph.ml/ols` uses Apache Commons Math
;; which Nippy can't serialize. `pocket-model` catches the
;; error and falls back to uncached training — the pipeline
;; still works, just without disk caching for that model.

(pocket/cleanup!)

(def fallback-results
  (ml/evaluate-pipelines
   [(mm/pipeline {:metamorph/id :model} (pocket-model cart-spec))
    (mm/pipeline {:metamorph/id :model} (pocket-model {:model-type :metamorph.ml/ols}))]
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false
    :return-best-pipeline-only false}))

;; CART models are cached — 3 entries, one per fold.
;; OLS falls back to uncached training silently. The failed
;; serialization attempts leave empty cache directories, which
;; show up as entries with a `nil` function name:

(pocket/cache-stats)

(kind/test-last
 [(fn [stats] (= 3 (get-in stats [:entries-per-fn "scicloj.metamorph.ml/train"])))])

;; Both model types produce valid metrics:

(let [model-names ["CART" "OLS-fallback"]
      means (mapv (fn [pipeline-results]
                    (tcc/mean (map #(-> % :test-transform :metric) pipeline-results)))
                  fallback-results)]
  (tc/dataset {:model model-names :mean-rmse means}))

(kind/test-last
 [(fn [ds] (= 2 (tc/row-count ds)))])

;; ---

;; ## Disk persistence
;;
;; Models survive JVM restarts. After clearing the in-memory
;; cache, models are loaded from disk on next access.

(pocket/cleanup!)

;; Train fresh:
(def persist-results-1
  (ml/evaluate-pipelines
   [(mm/pipeline {:metamorph/id :model} (pocket-model cart-spec))]
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false
    :return-best-pipeline-only false}))

;; Clear in-memory cache (simulates JVM restart):
(pocket/clear-mem-cache!)

;; Re-evaluate — loads from disk:
(def persist-results-2
  (ml/evaluate-pipelines
   [(mm/pipeline {:metamorph/id :model} (pocket-model cart-spec))]
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false
    :return-best-pipeline-only false}))

;; Same metrics:
(= (mapv #(-> % :test-transform :metric) (flatten persist-results-1))
   (mapv #(-> % :test-transform :metric) (flatten persist-results-2)))

(kind/test-last
 [(fn [eq] (true? eq))])

;; ---

;; ## Discussion
;;
;; `pocket-model` is a thin wrapper — about 20 lines of code — that
;; gives us disk-persistent model caching with zero changes to our
;; pipeline structure. It works with `evaluate-pipelines`,
;; preprocessing steps, learning curves, and grid search.
;;
;; **Serialization compatibility** (tested):
;;
;; | Backend | Cacheable? |
;; |---------|-----------|
;; | Tribuo regression (CART, SGD) | Yes |
;; | Tribuo classification | Yes |
;; | fastmath/ols | Yes |
;; | metamorph.ml/ols (Commons Math) | No (falls back) |
;; | metamorph.ml/dummy-regressor | Yes |
;;
;; **When to use `pocket-model`**:
;; - Grid search / hyperparameter tuning (train once, reuse)
;; - Iterative notebook development (change downstream code, keep models)
;; - Learning curves (add new sizes, only new ones train)
;; - Any workflow where we re-evaluate with the same data + options

;; ## Cleanup

(pocket/cleanup!)
