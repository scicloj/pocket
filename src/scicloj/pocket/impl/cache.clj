(ns scicloj.pocket.impl.cache
  (:require [babashka.fs :as fs]
            [taoensso.nippy :as nippy]
            [clojure.core.cache :as cc]
            [clojure.core.cache.wrapped :as cw]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [scicloj.pocket.protocols :as protocols :refer [PIdentifiable ->id]]
            [scicloj.kindly.v4.kind :as kind])
  (:import (org.apache.commons.codec.digest DigestUtils)
           (clojure.lang IPersistentMap IDeref Var)
           (java.util IdentityHashMap)))

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
      (try
        (edn/read-string (slurp meta-path))
        (catch Exception e
          (log/warn "Corrupted meta.edn at" meta-path ":" (.getMessage e))
          nil)))))

(defn write-cached! [v path meta-map]
  (fs/create-dirs path)
  (cond
    ;; nil - use marker file (small, atomic enough via spit)
    (nil? v)
    (spit (str path "/nil") "")
    ;; else - write to temp, then atomic rename
    :else
    (let [target (str path "/value.nippy")
          tmp (str target ".tmp")]
      (nippy/freeze-to-file tmp v)
      (fs/move tmp target {:replace-existing true})))
  ;; meta.edn is small, spit is acceptable
  (when meta-map
    (write-meta! meta-map path))
  (log/debug "Cache write:" path))

(defn sha [^String s]
  (DigestUtils/sha1Hex s))

(def ^:private pocket-edn-cache
  "TTL cache for pocket.edn (1 second)."
  (atom (cc/ttl-cache-factory {} :ttl 1000)))

(defn pocket-edn
  "Read pocket.edn from classpath. Cached for 1 second to avoid repeated classpath scans."
  []
  (cw/lookup-or-miss
   pocket-edn-cache
   :pocket-edn
   (fn [_]
     (when-let [r (io/resource "pocket.edn")]
       (-> r slurp edn/read-string)))))

(def pocket-defaults-edn
  "Library defaults from pocket-defaults.edn. Read once at load time."
  (delay
    (when-let [r (io/resource "pocket-defaults.edn")]
      (-> r slurp edn/read-string))))

(def current-mem-cache-options
  "Tracks the options the mem-cache was last configured with."
  (atom (:mem-cache @pocket-defaults-edn)))

(defonce mem-cache
  (cw/lru-cache-factory {} :threshold (get-in @pocket-defaults-edn [:mem-cache :threshold])))

(def ^java.util.concurrent.ConcurrentHashMap in-flight
  "Ensures concurrent derefs of the same path compute only once."
  (java.util.concurrent.ConcurrentHashMap.))

(defn clear-mem-cache! []
  (swap! mem-cache empty))

(defn reset-mem-cache!
  "Reset the in-memory cache with the given options.
   
   Supported keys:
   - `:policy` — `:lru`, `:fifo`, `:lu`, `:ttl`, `:lirs`, `:soft`, or `:basic`
   - `:threshold` — max entries for `:lru`, `:fifo`, `:lu`
   - `:ttl` — time-to-live in ms for `:ttl` policy
   - `:s-history-limit` / `:q-history-limit` — for `:lirs` policy
   
   Defaults come from `pocket-defaults.edn`."
  [{:keys [policy threshold ttl s-history-limit q-history-limit]
    :or {policy :lru
         threshold (get-in @pocket-defaults-edn [:mem-cache :threshold])
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
            (throw (ex-info (str "Unknown cache policy: " policy) {:policy policy}))))
  (log/info "Mem-cache reconfigured:" opts)
  opts)

(defn ensure-mem-cache!
  "Reconfigure the mem-cache if `opts` differ from current configuration.
   If `opts` is nil, does nothing."
  [opts]
  (when (and opts (not= opts @current-mem-cache-options))
    (reset-mem-cache! opts)))

(defn ->path
  "Generate cache path from base-dir and id.
   The id is expected to be canonicalized (via `canonical-id`) before calling.
   filename-length-limit controls when to switch to SHA-1 hash (default 240)."
  ([base-dir id]
   (->path base-dir id 240))
  ([base-dir id filename-length-limit]
   (when-not base-dir
     (throw (ex-info "No cache directory configured. Set it via pocket/set-base-cache-dir!, the POCKET_BASE_CACHE_DIR env var, or pocket.edn."
                     {})))
   (let [idstr (-> (str id)
                   (str/replace "/" "_"))]
     (str base-dir
          "/"
          (subs (sha idstr) 0 2)
          "/"
          (if (> (count idstr) filename-length-limit)
            (sha idstr)
            idstr)))))
(defn canonical-id
  "Deep-sort all maps and sets in an id structure for canonical string representation.
   Walks the structure recursively: sorts map keys, recurses into sequential
   and map values. Preserves the type of sequentials (lists stay lists,
   vectors stay vectors). Sets are wrapped as (set [...]) to distinguish
   them from vectors with the same elements."
  [x]
  (cond
    (instance? IPersistentMap x)
    (->> x
         (sort-by key)
         (map (fn [[k v]] [k (canonical-id v)]))
         (into (array-map)))

    (sequential? x)
    (let [items (map canonical-id x)]
      (if (list? x)
        (apply list items)
        (vec items)))

    (set? x)
    (list 'set (vec (sort (map canonical-id x))))

    :else x))

(defn maybe-deref [x]
  (if (instance? IDeref x)
    @x
    x))

(defn- deref-with-cache
  "Shared deref logic for :mem and :mem+disk storage policies.
   `cached-obj` is the Cached instance (needed for ->id).
   `disk-read-fn` checks disk and returns a [found? value] pair, or nil to skip disk.
   `disk-write-fn` writes a computed value to disk, or nil to skip."
  [cached-obj disk-read-fn disk-write-fn]
  (let [base-dir (.base-dir cached-obj)
        f (.f cached-obj)
        args (.args cached-obj)
        filename-length-limit (.filename-length-limit cached-obj)
        id (canonical-id (->id cached-obj))
        fn-name (->id f)
        path (->path base-dir id filename-length-limit)]
    (cw/lookup-or-miss
     mem-cache path
     (fn [_]
       (let [d (.computeIfAbsent
                in-flight path
                (reify java.util.function.Function
                  (apply [_ _]
                    (delay
                      (try
                        (if-let [[_ disk-val] (when disk-read-fn
                                                (disk-read-fn path fn-name))]
                          disk-val
                          (do (log/info (if disk-read-fn
                                          "Cache miss, computing:"
                                          "Cache miss (mem), computing:")
                                        fn-name)
                              (let [resolved-args (mapv maybe-deref args)
                                    v (clojure.core/apply f resolved-args)]
                                (when disk-write-fn
                                  (disk-write-fn v path id fn-name args))
                                v)))
                        (finally
                          (.remove in-flight path)))))))]
         @d)))))

(deftype Cached [base-dir f args storage local-delay filename-length-limit]
  IDeref
  (deref [this]
    (case (or storage :mem+disk)
      :none
      @local-delay

      :mem
      (deref-with-cache this nil nil)

      :mem+disk
      (deref-with-cache
       this
       (fn [path fn-name]
         (when (fs/exists? path)
           (log/debug "Cache hit (disk):" fn-name path)
           [true (read-cached path)]))
       (fn [v path id fn-name args]
         (let [meta-map {:id (pr-str id)
                         :fn-name (str fn-name)
                         :args-str (pr-str (mapv ->id args))
                         :created-at (str (java.time.Instant/now))}]
           (write-cached! v path meta-map)))))))

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

(defmethod print-method Cached [^Cached c ^java.io.Writer w]
  (let [id (->id c)
        storage (or (.storage c) :mem+disk)]
    (case storage
      :none
      (let [realized? (and (.local-delay c) (realized? (.local-delay c)))]
        (.write w (str "#<Cached " (pr-str id) " " (if realized? :realized :none) ">")))

      :mem
      (if-let [base-dir (.base-dir c)]
        (let [path (->path base-dir (canonical-id id) (or (.filename-length-limit c) 240))
              status (if (contains? @mem-cache path) :cached :pending)]
          (.write w (str "#<Cached " (pr-str id) " " status ">")))
        (.write w (str "#<Cached " (pr-str id) ">")))

      :mem+disk
      (if-let [base-dir (.base-dir c)]
        (let [path (->path base-dir (canonical-id id) (or (.filename-length-limit c) 240))
              status (cond
                       (contains? @mem-cache path) :cached
                       (fs/exists? path) :disk
                       :else :pending)]
          (.write w (str "#<Cached " (pr-str id) " " status ">")))
        (.write w (str "#<Cached " (pr-str id) ">"))))))

(defn- peek-value
  "Non-computing peek at a Cached's realized value.
   Returns the value if already computed, or ::unrealized."
  [^Cached c]
  (let [storage (or (.storage c) :mem+disk)]
    (case storage
      :none
      (if (and (.local-delay c) (realized? (.local-delay c)))
        @(.local-delay c)
        ::unrealized)

      (:mem :mem+disk)
      (if-let [base-dir (.base-dir c)]
        (let [id (canonical-id (->id c))
              path (->path base-dir id (or (.filename-length-limit c) 240))]
          (cond
            (contains? @mem-cache path)
            (get @mem-cache path)

            (and (= storage :mem+disk) (fs/exists? path))
            (read-cached path)

            :else
            ::unrealized))
        ::unrealized))))

(defn- origin-story*
  "Internal recursive walk with seen tracking.
   `seen` is an IdentityHashMap mapping Cached instances to their node IDs."
  [x ^IdentityHashMap seen counter]
  (if (instance? Cached x)
    (if-let [existing-id (.get seen x)]
      ;; Already seen this exact Cached instance — emit reference
      {:ref existing-id}
      ;; First encounter — assign ID and recurse
      (let [node-id (str "c" (swap! counter inc))
            _ (.put seen x node-id)
            node {:fn (.f x)
                  :args (mapv #(origin-story* % seen counter) (.args x))
                  :id node-id}
            v (peek-value x)]
        (if (= v ::unrealized)
          node
          (assoc node :value v))))
    {:value x}))

(defn origin-story
  "Walk a Cached value's argument tree and return a DAG description.
   
   Returns a kindly-wrapped value for notebook rendering.
   
   Each cached step is `{:fn <var> :args [<nodes>] :id <string>}`,
   with `:value` included if the computation has been realized.
   Plain (non-Cached) arguments become `{:value <val>}` leaf nodes.
   
   When the same Cached instance appears multiple times in the tree,
   subsequent occurrences are represented as `{:ref <id>}` pointing
   to the first occurrence's `:id`. This enables proper DAG representation
   for diamond dependencies.
   
   Does not trigger computation — only peeks at already-realized values.
   Works with all storage policies (`:mem+disk`, `:mem`, `:none`)."
  [x]
  (kind/pprint (origin-story* x (IdentityHashMap.) (atom 0))))

(defn origin-story-graph
  "Walk a Cached value's argument tree and return a normalized graph.
   
   Returns a kindly-wrapped `{:nodes {<id> <node-map>} :edges [[<from> <to>] ...]}`.
   
   Node maps contain `:fn` (for cached steps) or `:value` (for leaves),
   plus `:value` if the cached computation has been realized.
   
   This is the fully normalized (Format B) representation of the DAG.
   Use `origin-story` for the tree-with-refs representation (Format A)."
  [x]
  (let [nodes (atom {})
        edges (atom [])
        counter (atom 0)
        ^IdentityHashMap seen (IdentityHashMap.)]
    (letfn [(walk [node parent-id]
              (if (instance? Cached node)
                (if-let [existing-id (.get seen node)]
                  ;; Already seen — just add edge
                  (when parent-id
                    (swap! edges conj [parent-id existing-id]))
                  ;; First encounter
                  (let [node-id (str "c" (swap! counter inc))
                        _ (.put seen node node-id)
                        v (peek-value node)
                        node-map (cond-> {:fn (.f node)}
                                   (not= v ::unrealized) (assoc :value v))]
                    (swap! nodes assoc node-id node-map)
                    (when parent-id
                      (swap! edges conj [parent-id node-id]))
                    (doseq [arg (.args node)]
                      (walk arg node-id))))
                ;; Plain value leaf
                (let [leaf-id (str "v" (swap! counter inc))]
                  (swap! nodes assoc leaf-id {:value node})
                  (when parent-id
                    (swap! edges conj [parent-id leaf-id])))))]
      (walk x nil)
      (kind/pprint {:nodes @nodes :edges @edges}))))

(defn- mermaid-escape
  "Escape a string for use in a Mermaid node label."
  [s]
  (-> s
      (str/replace "\"" "'")
      (str/replace "\n" " ")))

(defn origin-story-mermaid
  "Render an origin-story DAG as a Mermaid flowchart string.
   
   Returns a string with kindly metadata for notebook rendering.
   
   Node shapes distinguish types:
   - Functions (Cached): rectangles
   - Plain values: parallelograms (input data)
   
   Handles both tree format and DAG format (with :id/:ref nodes).
   Shared nodes (via :ref) are rendered as edges to existing nodes."
  [tree]
  (let [node-ids (atom {}) ;; maps origin-story :id to mermaid node id
        counter (atom 0)
        lines (atom [])
        gen-id #(let [n @counter] (swap! counter inc) (str "n" n))]
    (letfn [(walk [node]
              (cond
                ;; Reference to existing node
                (:ref node)
                (get @node-ids (:ref node))

                ;; Cached node (has :fn) - rectangle shape
                (:fn node)
                (let [mermaid-id (gen-id)
                      label (-> (:fn node) symbol name)]
                  ;; Register this node's origin-story :id if present
                  (when-let [os-id (:id node)]
                    (swap! node-ids assoc os-id mermaid-id))
                  (swap! lines conj (str "  " mermaid-id "[\"" (mermaid-escape label) "\"]"))
                  (doseq [arg (:args node)]
                    (let [child-id (walk arg)]
                      (swap! lines conj (str "  " child-id " --> " mermaid-id))))
                  mermaid-id)

                ;; Value leaf node - parallelogram shape (input data)
                :else
                (let [mermaid-id (gen-id)
                      v (:value node)
                      label (pr-str v)
                      label (if (> (count label) 40)
                              (str (subs label 0 37) "...")
                              label)]
                  (swap! lines conj (str "  " mermaid-id "[/\"" (mermaid-escape label) "\"/]"))
                  mermaid-id)))]
      (walk tree)
      (kind/mermaid (str "flowchart TD\n" (str/join "\n" @lines))))))
(defn cached
  "Create a cached computation"
  [base-dir storage filename-length-limit func & args]
  (when-not (var? func)
    (throw (ex-info (str "pocket/cached requires a var (e.g., #'my-fn), got: " (type func))
                    {:func func})))
  (let [storage (or storage :mem+disk)
        limit (or filename-length-limit 240)
        local-delay (when (= storage :none)
                      (delay (clojure.core/apply func (mapv maybe-deref args))))]
    (->Cached base-dir func args storage local-delay limit)))

(defn invalidate!
  "Invalidate a specific cached computation by deleting its disk and memory entries.
   Returns a map with `:path` and `:existed`."
  [base-dir filename-length-limit func args]
  (let [c (->Cached base-dir func args nil nil nil)
        id (canonical-id (->id c))
        limit (or filename-length-limit 240)
        path (->path base-dir id limit)
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
  "Scan the cache directory and return a vector of maps describing each entry.
   Each map contains `:path` and any metadata from `meta.edn`.
   Returns an empty vector if the cache directory doesn't exist.
   Optionally filter by function name."
  ([base-dir]
   (cache-entries base-dir nil))
  ([base-dir fn-name]
   (if (fs/exists? base-dir)
     (into []
           (for [prefix-dir (fs/list-dir base-dir)
                 :when (fs/directory? prefix-dir)
                 entry-dir (fs/list-dir prefix-dir)
                 :when (fs/directory? entry-dir)
                 :let [path (str entry-dir)
                       meta-map (read-meta path)]
                 :when (or (nil? fn-name)
                           (= fn-name (:fn-name meta-map)))]
             (merge {:path path} meta-map)))
     [])))

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

(defn- collect-params
  "Walk an origin-story tree and collect parameter maps from value leaves.
   Map values are returned as-is. Scalar values (numbers, strings, keywords,
   etc.) are wrapped as `{:<fn-name>/<arg-position> value}` using the parent
   function name and argument position for the key."
  ([tree] (collect-params tree nil nil))
  ([tree parent-fn-name arg-idx]
   (cond
     ;; Reference - skip (we'll find the original)
     (:ref tree) []
     ;; Cached node - recurse into args with position info
     (:fn tree) (let [fn-name (some-> (:fn tree) ->id name)]
                  (into []
                        (mapcat (fn [i arg]
                                  (collect-params arg fn-name i))
                                (range)
                                (:args tree))))
     ;; Value leaf
     :else (let [v (:value tree)]
             (cond
               (map? v) [v]
               (some? v) (if (and parent-fn-name arg-idx)
                           [{(keyword parent-fn-name (str "arg" arg-idx)) v}]
                           [{:_arg v}])
               :else [])))))

(defn- find-varying-keys
  "Given a seq of param maps, find keys whose values differ across maps."
  [param-maps]
  (let [all-keys (set (mapcat keys param-maps))]
    (set (filter (fn [k]
                   (let [vals (distinct (map #(get % k ::missing) param-maps))]
                     (> (count vals) 1)))
                 all-keys))))

(defn compare-experiments
  "Compare multiple cached experiment results.
   
   Takes a seq of `Cached` values (typically final metrics from different
   hyperparameter configurations). Walks each experiment's origin-story
   to extract parameter maps, identifies which parameters vary across
   experiments, and returns a seq of maps containing the varying params
   plus the experiment result.
   
   Only parameters that differ across experiments are included.
   The `:result` key contains the derefed value of each Cached."
  [cached-values]
  (let [stories (mapv origin-story cached-values)
        all-params (mapv collect-params stories)
        ;; Merge all params for each experiment
        merged-params (mapv (fn [params] (apply merge params)) all-params)
        ;; Find which keys vary
        varying-keys (find-varying-keys merged-params)]
    (mapv (fn [cached merged]
            (-> (select-keys merged varying-keys)
                (assoc :result @cached)))
          cached-values merged-params)))

