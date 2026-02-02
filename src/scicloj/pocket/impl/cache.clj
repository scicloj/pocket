(ns scicloj.pocket.impl.cache
  (:require [babashka.fs :as fs]
            [taoensso.nippy :as nippy]
            [clojure.core.cache :as cc]
            [clojure.core.cache.wrapped :as cw]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [scicloj.pocket.protocols :as protocols :refer [PIdentifiable ->id]])
  (:import (org.apache.commons.codec.digest DigestUtils)
           (clojure.lang IPersistentMap IDeref Var)))

(defn read-cached [path]
  (cond
    (fs/exists? (str path "/value.nippy"))
    (nippy/thaw-from-file (str path "/value.nippy"))

    (fs/exists? (str path "/nil"))
    nil

    :else
    (throw (ex-info (str "Corrupted cache entry: no value.nippy or nil marker at " path)
                    {:path path}))))

(defn write-meta! [meta-map path]
  (spit (str path "/meta.edn") (pr-str meta-map)))

(defn read-meta [path]
  (let [meta-path (str path "/meta.edn")]
    (when (fs/exists? meta-path)
      (edn/read-string (slurp meta-path)))))

(defn write-cached! [v path meta-map]
  (fs/create-dirs path)
  (cond
    ;; nil
    (nil? v)
    (spit (str path "/nil") "")
    ;; else
    :else
    (nippy/freeze-to-file (str path "/value.nippy") v))
  (when meta-map
    (write-meta! meta-map path))
  (log/debug "Cache write:" path))

(defn sha [^String s]
  (DigestUtils/sha1Hex s))

(def pocket-edn
  "Cached pocket.edn from classpath. Read once on first access."
  (delay
    (when-let [r (io/resource "pocket.edn")]
      (-> r slurp edn/read-string))))

(def default-mem-cache-options
  {:policy :lru :threshold 256})

(def current-mem-cache-options
  "Tracks the options the mem-cache was last configured with."
  (atom default-mem-cache-options))

(defonce mem-cache
  (cw/lru-cache-factory {} :threshold (:threshold default-mem-cache-options)))

(def ^java.util.concurrent.ConcurrentHashMap in-flight
  "Ensures concurrent derefs of the same path compute only once."
  (java.util.concurrent.ConcurrentHashMap.))

(defn clear-mem-cache! []
  (swap! mem-cache empty))

(defn reset-mem-cache!
  "Reset the in-memory cache with the given options.
   
   `:policy` — one of `:lru`, `:fifo`, `:lu`, `:ttl`, `:lirs`, `:soft`, `:basic` (default `:lru`).
   `:threshold` — max entries for `:lru`, `:fifo`, `:lu` (default 256).
   `:ttl` — time-to-live in ms for `:ttl` policy (default 30000).
   `:s-history-limit` / `:q-history-limit` — for `:lirs` policy."
  [{:keys [policy threshold ttl s-history-limit q-history-limit]
    :or {policy :lru
         threshold (:threshold default-mem-cache-options)
         ttl 30000}
    :as opts}]
  (reset! current-mem-cache-options opts)
  (reset! mem-cache
          (case policy
            :basic (cc/basic-cache-factory {})
            :fifo (cc/fifo-cache-factory {} :threshold threshold)
            :lru (cc/lru-cache-factory {} :threshold threshold)
            :lu (cc/lu-cache-factory {} :threshold threshold)
            :ttl (cc/ttl-cache-factory {} :ttl ttl)
            :lirs (cc/lirs-cache-factory {} :s-history-limit (or s-history-limit threshold)
                                         :q-history-limit (or q-history-limit (quot threshold 4)))
            :soft (cc/soft-cache-factory {})
            (throw (ex-info (str "Unknown cache policy: " policy)
                            {:policy policy}))))
  (log/info "Mem-cache reconfigured:" opts)
  opts)

(defn ensure-mem-cache!
  "Reconfigure the mem-cache if `opts` differ from current configuration.
   If `opts` is nil, does nothing."
  [opts]
  (when (and opts (not= opts @current-mem-cache-options))
    (reset-mem-cache! opts)))

(defn ->path [base-dir id]
  (when-not base-dir
    (throw (ex-info "No cache directory configured. Set it via pocket/set-base-cache-dir!, the POCKET_BASE_CACHE_DIR env var, or pocket.edn."
                    {})))
  (let [h (-> id hash str)]
    (str base-dir
         "/.cache/"
         (-> h sha (subs 0 2))
         "/"
         (let [idstr (-> (str id)
                         (str/replace "/" "⁄"))]
           (if (-> idstr count (> 240))
             (sha h)
             idstr)))))

(defn canonical-id
  "Deep-sort all maps and normalize sequentials in an id structure
   for canonical string representation. Walks the structure recursively:
   sorts map keys, recurses into sequential and map values. Sequentials
   whose first element is a symbol are kept as lists (so cache paths
   look like function calls); all others are coerced to vectors."
  [x]
  (cond
    (instance? IPersistentMap x)
    (->> x
         (sort-by key)
         (map (fn [[k v]] [k (canonical-id v)]))
         (into (array-map)))

    (sequential? x)
    (let [items (map canonical-id x)]
      (if (symbol? (first items))
        (apply list items)
        (vec items)))

    (set? x)
    x

    :else x))

(defn maybe-deref [x]
  (if (instance? IDeref x)
    @x
    x))

(deftype Cached [base-dir f args]
  IDeref
  (deref [this]
    (let [id (canonical-id (->id this))
          fn-name (->id f)
          path (->path base-dir id)]
      (cw/lookup-or-miss
       mem-cache path
       (fn [_]
         ;; Use in-flight to ensure concurrent derefs of the same path
         ;; compute only once. lookup-or-miss deduplicates per-call via
         ;; its own delay, but concurrent callers each create separate
         ;; lookup-or-miss invocations, so we need this extra layer.
         (let [d (.computeIfAbsent
                  in-flight path
                  (reify java.util.function.Function
                    (apply [_ _]
                      (delay
                        (try
                          (if (fs/exists? path)
                            (do (log/debug "Cache hit (disk):" fn-name path)
                                (read-cached path))
                            (do (log/info "Cache miss, computing:" fn-name)
                                (let [resolved-args (mapv maybe-deref args)
                                      v (clojure.core/apply f resolved-args)
                                      meta-map {:id (pr-str id)
                                                :fn-name (str fn-name)
                                                :args-str (pr-str (mapv ->id args))
                                                :created-at (str (java.time.Instant/now))}]
                                  (write-cached! v path meta-map)
                                  v)))
                          (finally
                            (.remove in-flight path)))))))]
           @d))))))

(extend-protocol PIdentifiable
  Cached
  (->id [v]
    (apply list
           (->id (.f v))
           (map ->id (.args v))))

  clojure.lang.MapEntry
  (->id [v] [(->id (key v)) (->id (val v))])

  Var
  (->id [this]
    (-> this symbol))

  Object
  (->id [this]
    this)

  nil
  (->id [_] nil))

(defn cached
  "Create a cached computation"
  [base-dir func & args]
  (when-not (var? func)
    (throw (ex-info (str "pocket/cached requires a var (e.g., #'my-fn), got: " (type func))
                    {:func func})))
  (->Cached base-dir func args))

(defn invalidate!
  "Invalidate a specific cached computation by deleting its disk and memory entries.
   Returns a map with `:path` and `:existed`."
  [base-dir func args]
  (let [c (->Cached base-dir func args)
        id (canonical-id (->id c))
        path (->path base-dir id)
        existed? (boolean (fs/exists? path))]
    (when existed?
      (fs/delete-tree path))
    (swap! mem-cache dissoc path)
    (.remove in-flight path)
    (log/info "Invalidated:" path "existed=" existed?)
    {:path path :existed existed?}))

(declare cache-entries)

(defn invalidate-fn!
  "Invalidate all cached entries for a given function var.
   Uses cache-entries (metadata-based) to find matching entries,
   so this works even for SHA-1 hashed paths.
   Returns a map with `:fn-name`, `:count`, and `:paths`."
  [base-dir func]
  (let [fn-name (str (->id func))
        entries (cache-entries base-dir fn-name)
        deleted-paths (mapv (fn [{:keys [path]}]
                              (fs/delete-tree path)
                              (swap! mem-cache dissoc path)
                              (.remove in-flight path)
                              path)
                            entries)]
    (log/info "Invalidated" (count deleted-paths) "entries for" fn-name)
    {:fn-name fn-name
     :count (count deleted-paths)
     :paths deleted-paths}))

(defn cache-entries
  "Scan the cache directory and return a sequence of metadata maps.
   Each map contains `:path` and any metadata from `meta.edn`.
   Optionally filter by function name."
  ([base-dir]
   (cache-entries base-dir nil))
  ([base-dir fn-name]
   (let [cache-dir (str base-dir "/.cache")]
     (when (fs/exists? cache-dir)
       (into []
             (for [prefix-dir (fs/list-dir cache-dir)
                   :when (fs/directory? prefix-dir)
                   entry-dir (fs/list-dir prefix-dir)
                   :when (fs/directory? entry-dir)
                   :let [path (str entry-dir)
                         meta-map (read-meta path)]
                   :when (or (nil? fn-name)
                             (= fn-name (:fn-name meta-map)))]
               (merge {:path path} meta-map)))))))

(defn cache-stats
  "Return aggregate statistics about the cache.
   Returns a map with `:total-entries`, `:total-size-bytes`,
   and `:entries-per-fn`."
  [base-dir]
  (let [entries (or (cache-entries base-dir) [])
        size-fn (fn [path]
                  (->> (fs/list-dir path)
                       (map #(fs/size %))
                       (reduce + 0)))]
    {:total-entries (count entries)
     :total-size-bytes (reduce + 0 (map #(size-fn (:path %)) entries))
     :entries-per-fn (->> entries
                          (group-by :fn-name)
                          (reduce-kv (fn [m k v] (assoc m k (count v))) {}))}))

(defn dir-tree
  "Render a directory as a tree string, like the Unix `tree` command.
   Returns a string showing the hierarchical structure of files and directories."
  [dir]
  (let [root (clojure.java.io/file dir)
        sb (StringBuilder.)]
    (.append sb (.getName root))
    (.append sb "\n")
    (letfn [(walk [f prefix last?]
              (.append sb prefix)
              (.append sb (if last? "└── " "├── "))
              (.append sb (.getName f))
              (.append sb "\n")
              (when (.isDirectory f)
                (let [children (sort-by #(.getName %) (vec (.listFiles f)))
                      n (count children)]
                  (doseq [[i child] (map-indexed vector children)]
                    (walk child
                          (str prefix (if last? "    " "│   "))
                          (= i (dec n)))))))]
      (let [children (sort-by #(.getName %) (vec (.listFiles root)))
            n (count children)]
        (doseq [[i child] (map-indexed vector children)]
          (walk child "" (= i (dec n))))))
    (str sb)))

