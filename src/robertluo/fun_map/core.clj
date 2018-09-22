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
            APersistentMap]))

(defprotocol ValueWrapper
  (unwrap [this m k]
    "unwrap the real value from a wrapper on the key of k"))

(defn value-wrapper?
  [o]
  (satisfies? ValueWrapper o))

(defn deep-unwrap [o m k]
  (if (value-wrapper? o)
    (recur (unwrap o m k) m k)
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
      (delegate-map (.dissoc m k)))

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
  (unwrap [d _ _]
    (deref d)))

(deftype FunctionWrapper [f]
  clojure.lang.IFn
  (invoke [_ ^Object m]
    (if (instance? java.util.Map m)
      (f m ::impossible)
      (throw (IllegalArgumentException.
              "FunctionWrapper's argument must be a map"))))
  ValueWrapper
  (unwrap [_ m k]
    (f m k)))

(defn fun-wrapper
  "returns a new FunctionWrapper with single argument function f"
  [f]
  (->FunctionWrapper (fn [m _] (f m))))

(defn cached
  "a remember last argument cached function middleware"
  [f a-val-pair focus-fn]
  (fn [m k]
    (let [[val focus-val] @a-val-pair
          new-focus-val (if focus-fn (focus-fn m) ::unrealized)]
      (if (or (= ::unrealized val) (not= new-focus-val focus-val))
        (first (swap! a-val-pair (fn [_] [(f m k) new-focus-val])))
        val))))

(defn traced
  "trace funtion call"
  [f trace-fn]
  (fn [m k]
    (let [v (f m k)]
      (when-let [trace-fn (or trace-fn (some-> m meta ::trace))]
        (trace-fn k v))
      v)))

(defn tf-fun-wrapper
  "A traced, cached last implementation of fun-wrapper"
  [f trace-fn focus-fn]
  (->FunctionWrapper
   (-> (fn [m _] (f m))
       (traced trace-fn)
       (cached (atom [::unrealized ::unrealized]) focus-fn))))

;;;;;;;;;; fw macro implementation

(defmulti fw-impl
  "returns a form for fw macro implementation"
  :impl)

(defn fw-impl-tf-wrapper
  [{:keys [f arg-map options]}]
  (let [{:keys [focus trace]} options]
    `(tf-fun-wrapper
      ~f
      ~(when trace trace)
      ~(when focus `(fn [~arg-map] ~focus)))))

(defmethod fw-impl :naive [{:keys [f]}]
  `(fun-wrapper ~f))

(defmethod fw-impl :trace-cache [m]
  (fw-impl-tf-wrapper m))

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
