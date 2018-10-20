(ns ^:no-doc robertluo.fun-map.core
  "implementation of fun-maps.

  a fun-map delegates its storage to underlying map m,
  m stores k,v pair with value wrapped inside a wrapper,
  when requested an entry from a fun-map, instead of
  returning map entry like an ordinary map, it returns
  a special version of entry, evaluate it's value by
  invoking the wrapper."
  (:import [clojure.lang
            IMapEntry
            APersistentMap])
  (:require [robertluo.fun-map.util :as util]))

(defprotocol ValueWrapper
  (-unwrap [this m k]
    "unwrap the real value from a wrapper on the key of k"))

(defn value-wrapper?
  [o]
  (satisfies? ValueWrapper o))

(defn deep-unwrap [o m k]
  (if (value-wrapper? o)
    (recur (-unwrap o m k) m k)
    o))

(defn wrapped-entry [m ^IMapEntry entry]
  (proxy [clojure.lang.MapEntry] [(.key entry) (.val entry)]
    (val []
      (deep-unwrap (proxy-super val) m (.key this)))))

(definterface IFunMap
  (rawSeq []))

(defn delegate-map [^APersistentMap m]
  (proxy [APersistentMap clojure.lang.IObj IFunMap java.io.Closeable] []
    (meta []
      (.meta m))

    (rawSeq []
      (.seq m))

    (close []
      (when-let [close-fn (some-> (.meta this) ::close-fn)]
        (close-fn this)))

    (withMeta [mdata]
      (delegate-map (.withMeta m mdata)))

    (containsKey [k]
      (.containsKey m k))

    (valAt
      ([k]
       (some-> (.entryAt this k) (.val)))
      ([k not-found]
       (if (.containsKey this k)
         (.valAt this k)
         not-found)))

    (entryAt [k]
      (when (.containsKey m k)
        (wrapped-entry this (.entryAt m k))))

    (assoc [k v]
      (delegate-map (.assoc m k v)))

    (assocEx [k v]
      (delegate-map (.assocEx m k v)))

    (empty []
      (delegate-map (.empty m)))

    (without [k]
      (delegate-map (.without m k)))

    (count []
      (.count m))

    (iterator []
      (let [ite (.iterator m)]
        (reify java.util.Iterator
          (hasNext [_]
            (.hasNext ite))
          (next [_]
            (wrapped-entry this (.next ite))))))

    (cons [o]
      (proxy-super cons (if (instance? IFunMap o) (.rawSeq ^IFunMap o) o)))

    (seq []
      (clojure.lang.IteratorSeq/create (.iterator this)))))

;;;;;;;;;;;; ValueWrappers

(extend-protocol ValueWrapper
  clojure.lang.IDeref
  (-unwrap [d _ _]
    (deref d)))

(deftype FunctionWrapper [f]
  ValueWrapper
  (-unwrap [_ m k]
    (f m k)))

(defn fun-wrapper
  "returns a new FunctionWrapper with single argument function f"
  [f]
  (->FunctionWrapper (fn [m _] (f m))))

;;;;;;;;;;; Wrapper of wrapper

(deftype CachedWrapper [wrapped a-val-pair focus-fn]
  ValueWrapper
  (-unwrap [_ m k]
    (let [[val focus-val] @a-val-pair
          new-focus-val (if focus-fn (focus-fn m) ::unrealized)]
      (if (or (= ::unrealized val) (not= new-focus-val focus-val))
        (first (swap! a-val-pair (fn [_] [(-unwrap wrapped m k) new-focus-val])))
        val))))

(defn cache-wrapper
  [wrapped focus]
  (CachedWrapper. wrapped (atom [::unrealized ::unrealized]) focus))

(deftype TracedWrapper [wrapped trace-fn]
  ValueWrapper
  (-unwrap [_ m k]
    (let [v (-unwrap wrapped m k)]
      (when-let [trace-fn (or trace-fn (some-> m meta ::trace))]
        (trace-fn k v))
      v)))

(defn trace-wrapper
  [wrapped trace]
  (TracedWrapper. wrapped trace))

;;;;;;;;;; fw macro implementation

(defn destruct-map
  "destruct arg-map of fw macro into different groups"
  [arg-map]
  (reduce
   (fn [rst [k v]]
     (cond
       (= k :keys)
       (update rst :naming into (map (fn [s] [(-> s name symbol) (keyword s)]) v))

       (symbol? k)
       (update rst :naming assoc k v)

       (#{:as :or} k)
       (update rst :normal assoc k v)

       (and (keyword? k) (= "keys" (name k)))
       (let [ns (namespace k)]
         (update rst :naming into (map (fn [s] [s (keyword ns (name s))]) v)))

       :else
       (update rst :fm assoc k v)))
   {:naming {} :normal {} :fm {}}
   arg-map))

(defn make-binding
  "prepare binding for let"
  [naming {:keys [or as]}]
  (cond-> (mapcat
           (fn [[sym k]]
             (let [g `(get ~'m ~k)
                   kf (if-let [v (get or sym)] `(or ~g ~v) g)]
               [sym kf]))
           naming)
    as (concat [as 'm])
    true vec))

(defmulti fw-impl
  "returns a form for fw macro implementation"
  :impl)

(defn let-form
  "returns a pair first to let or equivalent, and the second to transformed bindings."
  [_ bindings]
  [`let bindings])

(def default-wrappers
  "Default wrappers for fw macro"
  [:trace :cache])

(defn make-fw-wrapper
  "construct fw"
  [arg-map body]
  (let [{:keys [naming normal fm]} (destruct-map arg-map)
        arg-map (merge naming normal)
        [lets binding] (let-form fm (make-binding naming normal))
        f `(fn [~'m] (~lets ~binding ~@body))]
    (reduce (fn [rst wrapper]
              (fw-impl {:impl wrapper :arg-map arg-map :f rst :options fm}))
            `(fun-wrapper ~f)
            (or (:wrappers fm) default-wrappers))))

(defmethod fw-impl :trace
  [{:keys [f options]}]
  `(trace-wrapper ~f ~(:trace options)))

(defmethod fw-impl :cache
  [{:keys [f options arg-map]}]
  (let [focus (when-let [focus (:focus options)]
                `(fn [~arg-map] ~focus))]
    `(cache-wrapper ~f ~focus)))

;;;;;;;;;;; Utilities

(deftype CloseableValue [value close-fn]
  clojure.lang.IDeref
  (deref [_]
    value)
  java.io.Closeable
  (close [_]
    (close-fn)))

(defn fun-map?
  [o]
  (instance? IFunMap o))

(defmethod print-method FunctionWrapper [^FunctionWrapper o ^java.io.Writer wtr]
  (.write wtr (str "<<" (.f o) ">>")))

(defmethod print-method CachedWrapper [^CachedWrapper o ^java.io.Writer wtr]
  (.write wtr
          (str "<<"
               (let [v (-> (.a_val_pair o) deref first)]
                 (if (= ::unrealized v) "unrealized" v))
               ">>")))

(defmethod print-method IFunMap [^IFunMap o ^java.io.Writer wtr]
  (let [raw-entries (.rawSeq o)]
    (print-method (into {} raw-entries) wtr)))

(prefer-method print-method IFunMap clojure.lang.IPersistentMap)
(prefer-method print-method IFunMap java.util.Map)

;;;;;;;;;;;; Spec your FunctionWrapper

(util/opt-require [clojure.spec.alpha :as s]
  (deftype SpecCheckingWrapper [wrapped spec]
    ValueWrapper
    (-unwrap [_ m k]
      (let [v (-unwrap wrapped m k)]
        (if (s/valid? spec v)
          v
          (throw (ex-info "Value unwrapped does not conform spec"
                          {:key k :value v :explain (s/explain-data spec v)}))))))

  (defn spec-wrapper
    [wrapped spec]
    (SpecCheckingWrapper. wrapped spec))

  (def default-wrappers
    "Redefine default wrappers to support spec"
    [:spec :trace :cache])

  (defmethod fw-impl :spec
    [{:keys [f options]}]
    (let [spec (:spec options)]
      (if spec `(spec-wrapper ~f ~spec) f))))

;;;;;;;;;;;;;;;; Parallel support

(util/opt-require [manifold.deferred]
  (defn let-form
    "redefine let-form check if :par? is truthy, then use manifold's let-flow
     to replace let, and create future for value."
    [fm bindings]
    (if (:par? fm)
      [`manifold.deferred/let-flow
       (->> bindings
            (partition 2)
            (mapcat (fn [[k v]] [k `(manifold.deferred/future ~v)]))
            vec)]
      [`let bindings])))
