(ns scicloj.pocket.impl.cache
  (:require [babashka.fs :as fs]
            [taoensso.nippy :as nippy])
  (:import (org.apache.commons.codec.digest DigestUtils)
           (clojure.lang PersistentHashMap IDeref Var)))

(defprotocol PIdentifiable
  (->id [this]))

(defn read-cached [path]
  (cond
    ;; nippy
    (fs/exists? (str path "/_.nippy"))
    (nippy/thaw-from-file (str path "/_.nippy"))
    ;; nil
    (fs/exists? (str path "/nil"))
    (do
      #_(fs/delete (str path "/nil"))
      nil)))

(defn write-cached! [v path]
  (fs/create-dirs path)
  (cond
    ;; nil
    (nil? v)
    (spit (str path "/nil") "")
    ;; else
    :else
    (nippy/freeze-to-file (str path "/_.nippy") v)))

(defn sha [^String s]
  (DigestUtils/sha1Hex s))

(defn ->path [base-dir id typ]
  (let [h (-> id
              hash-unordered-coll
              str)]
    (str base-dir
         "/.cache/"
         (-> h
             sha
             (subs 0 2))
         "/"
         (let [idstr (str id)]
           (if (-> idstr
                   count
                   (> 250))
             (sha h)
             idstr)))))

(deftype Cached [base-dir f args]
  IDeref
  (deref [this]
    (let [id (->id this)
          path (->path base-dir id
                       (-> f meta :type))]
      (if (fs/exists? path)
        (read-cached path)
        (let [v (apply f args)]
          (write-cached! v path)
          v)))))

(extend-protocol PIdentifiable
  Cached
  (->id [v]
    (list
     (->id (.f v))
     (->id (->> v
                .args
                (mapv (fn [a]
                        (cond (nil? a) nil
                              (instance? PersistentHashMap a) (->> a
                                                                   (sort-by key)
                                                                   (mapcat (fn [[k v]]
                                                                             [k (->id v)]))
                                                                   (apply array-map))
                              :else (->id a))))))))

  clojure.lang.MapEntry
  (->id [v] (pr-str v))

  Var
  (->id [this]
    (-> this symbol name))

  Object
  (->id [this]
    this))

(defn cached
  "Create a cached computation"
  [base-dir func & args]
  (->Cached base-dir func args))

(defn maybe-deref [x]
  (if (instance? IDeref x)
    @x
    x))

(defn cached-fn [base-dir f]
  (fn [& args]
    (apply cached base-dir f args)))

;; Fix: Add nil handling to PIdentifiable protocol
(extend-protocol PIdentifiable
  nil
  (->id [_] nil))
