(ns nomad.config
  (:require #?@(:clj [[buddy.core.codecs :as bc]
                      [buddy.core.codecs.base64 :as b64]
                      [buddy.core.crypto :as b]
                      [buddy.core.nonce :as bn]
                      [clojure.tools.reader.edn :as edn]])

            [clojure.set :as set]
            [clojure.string :as s]
            [clojure.walk :as w]))

(def ^:dynamic *opts* nil)
(def !clients (atom {}))

(do
  #?@(:clj
      [(defrecord Secret [key-id cipher-text])

       (defn generate-key []
         (bc/bytes->str (b64/encode (bn/random-bytes 32))))

       (def ^:private block-size
         (b/block-size (b/block-cipher :aes :cbc)))

       (defn- resolve-secret-key [secret-key]
         (cond
           (string? secret-key) secret-key
           (keyword? secret-key) (or (get (:secret-keys *opts*) secret-key)
                                     (throw (ex-info "missing secret-key" {"secret-key" secret-key})))
           :else (throw (ex-info "invalid secret-key" {:secret-key secret-key}))))

       (defn encrypt [secret-key plain-obj]
         (let [iv (bn/random-bytes block-size)]
           (->> [iv (b/encrypt (bc/str->bytes (pr-str plain-obj)) (b64/decode (bc/str->bytes (resolve-secret-key secret-key))) iv)]
                (mapcat seq)
                byte-array
                b64/encode
                bc/bytes->str)))

       (defn decrypt [secret-key cipher-text]
         (let [[iv cipher-bytes] (map byte-array (split-at block-size (b64/decode cipher-text)))]
           (edn/read-string (b/decrypt cipher-bytes (b64/decode (bc/str->bytes (resolve-secret-key secret-key))) iv))))]))

(defmacro switch
  "Takes a set of switch/expr clauses, and an optional default value.
  Returns the configuration from the first active switch, or the default if none are active, or nil.

  (n/switch
    <switch> <expr>
    <switch-2> <expr-2>
    ...
    <default-expr>)"
  {:style/indent 0}
  [& opts+clauses]
  (let [switches-sym (gensym 'switches)
        [opts clauses] (if (map? (first opts+clauses))
                         [(first opts+clauses) (rest opts+clauses)]
                         [{} opts+clauses])]
    `(let [{override-key# ::override-key} ~opts
           ~switches-sym (set (:switches *opts*))]
       (cond
         ~@(for [[clause expr] (partition 2 clauses)
                 form [`(some ~switches-sym ~(cond
                                               (set? clause) clause
                                               (keyword? clause) #{clause}))
                       expr]]
             form)
         :else ~(when (pos? (mod (count clauses) 2))
                  (last clauses))))))

(defmacro defconfig [sym config]
  (let [config-sym (gensym 'config)
        set-var-sym (gensym (symbol (str "set-" sym "-config-var!")))
        fq-sym (symbol (str *ns*) (name sym))]
    `(do
       (declare ^:dynamic ~sym)

       (let [~config-sym (-> (fn [opts#]
                               (binding [*opts* (merge opts#
                                                       (when-let [override-switches# (get-in opts# [:override-switches ~fq-sym])]
                                                         {:switches override-switches#}))]
                                 ~config))
                             memoize)

             ret# (def ~(-> sym (with-meta {:dynamic true}))
                    (when *opts*
                      (~config-sym *opts*)))]

         ~(when-not (:ns &env)
            `(alter-meta! (var ~fq-sym) assoc :nomad/eval-config ~config-sym))

         (swap! !clients assoc '~fq-sym ~(if (:ns &env)
                                           `{:eval-config ~config-sym
                                             :set-config-var! (fn [v#]
                                                                (set! ~fq-sym v#))
                                             :config-var (var ~fq-sym)}

                                           `{:eval-config (fn [opts#]
                                                            (when-let [eval-config# (some-> (resolve '~fq-sym)
                                                                                            meta
                                                                                            :nomad/eval-config)]
                                                              (eval-config# opts#)))

                                             :set-config-var! (fn [v#]
                                                                (when-let [config-var# (resolve '~fq-sym)]
                                                                  (alter-var-root config-var# (constantly v#))))

                                             :->config-var (fn []
                                                             (resolve '~fq-sym))}))

         ret#))))

(defn parse-switches [switches]
  (some-> switches
          (s/split #",")
          (->> (into [] (map keyword)))))

(defn set-defaults! [{:keys [switches secret-keys override-switches], :as defaults}]
  #?(:clj (alter-var-root #'*opts* merge defaults)
     :cljs (set! *opts* (merge *opts* defaults)))

  (doseq [{:keys [eval-config set-config-var!]} (vals @!clients)]
    (set-config-var! (eval-config *opts*))))

(defn with-config-override* [{:keys [switches secret-keys override-switches] :as opts-override} f]
  (let [opts-override (merge *opts* opts-override)]
    #?(:clj (with-bindings (into {}
                                 (keep (fn [{:keys [eval-config ->config-var]}]
                                         (when-let [config-var (->config-var)]
                                           [config-var (eval-config opts-override)])))
                                 (vals @!clients))
              (f))

       :cljs (let [clients @!clients
                   initial-vals (->> clients
                                     (into {} (map (juxt key (comp deref :config-var val)))))]
               (try
                 (doseq [{:keys [set-config-var! eval-config]} (vals clients)]
                   (set-config-var! (eval-config opts-override)))

                 (f)

                 (finally
                   (doseq [[sym {:keys [set-config-var!]}] clients]
                     (set-config-var! (get initial-vals sym)))))))))

(defmacro with-config-override
  {:arglists '([{:keys [switches secret-keys override-switches] :as opts-override} & body])}
  [opts & body]

  `(with-config-override* ~opts (fn [] ~@body)))
