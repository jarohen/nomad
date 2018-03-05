(ns nomad.config
  (:require [buddy.core.codecs :as bc]
            [buddy.core.codecs.base64 :as b64]
            [buddy.core.crypto :as b]
            [buddy.core.nonce :as bn]
            [clojure.set :as set]
            [clojure.tools.reader.edn :as edn]
            [clojure.string :as s]
            [clojure.walk :as w]))

(def ^:dynamic *opts* nil)
(def !clients (atom #{}))

(defrecord Secret [key-id cipher-text])

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
    (edn/read-string (b/decrypt cipher-bytes (b64/decode (bc/str->bytes (resolve-secret-key secret-key))) iv))))

(defmacro switch*
  "Takes a set of switch/expr clauses, and an optional default value.
  Returns the configuration from the first active switch, or the default if none are active, or nil.

  (n/switch* #{:active :switches}
    <switch> <expr>
    <switch-2> <expr-2>
    ...
    <default-expr>)"
  {:style/indent 1}
  [switches & clauses]

  `(condp #(contains? %2 %1) ~switches
     ~@clauses
     ~@(when (zero? (mod (count clauses) 2))
         [nil])))

(defmacro switch
  "Takes a set of switch/expr clauses, and an optional default value.
  Returns the configuration from the first active switch, or the default if none are active, or nil.

  (n/switch
    <switch> <expr>
    <switch-2> <expr-2>
    ...
    <default-expr>)"
  {:style/indent 0}
  [& clauses]
  `(switch* (:switches *opts*) ~@clauses))

(defn add-client! [var]
  (let [{var-ns :ns, var-name :name} (meta var)]
    (swap! !clients conj (symbol (str var-ns) (name var-name)))))

(defmacro defconfig [sym config]
  (let [config-sym (gensym 'config)]
    `(let [~config-sym (-> (fn [opts#]
                             (binding [*opts* opts#]
                               ~config))
                           memoize)]
       (doto (def ~(-> sym (with-meta {:dynamic true}))
               (when *opts*
                 (~config-sym *opts*)))
         (alter-meta! assoc :nomad/config ~config-sym)
         add-client!))))

(defn- parse-switch [switch]
  (if-let [[_ switch-ns switch-name] (re-matches #"(.+?)/(.+)" switch)]
    (keyword switch-ns switch-name)
    (keyword switch)))

(def env-switches
  (-> (or (System/getenv "NOMAD_SWITCHES")
          (System/getProperty "nomad.switches"))
      (some-> (s/split #","))
      (->> (into [] (map parse-switch)))))

(defn eval-config [config-var {:keys [switches secret-keys]}]
  ((:nomad/config (meta config-var)) {:switches switches, :secret-keys secret-keys}))

(defn set-defaults! [{:keys [switches secret-keys], :as defaults}]
  (alter-var-root #'*opts* merge defaults)
  (doseq [client @!clients]
    (when-let [config-var (resolve client)]
      (alter-var-root config-var (constantly (eval-config config-var {:switches switches, :secret-keys secret-keys}))))))

(defn with-config-override* [{:keys [switches secret-keys] :as opts-override} f]
  (let [opts-override (merge *opts* opts-override)
        run (reduce (fn [f client]
                      (fn []
                        (if-let [config-var (resolve client)]
                          (with-bindings {config-var (doto (eval-config config-var opts-override) (prn config-var))}
                            (f))
                          (f))))
                    (fn []
                      (f))
                    @!clients)]
    (binding [*opts* opts-override]
      (run))))

(doto (defmacro with-config-override [opts & body]
        `(with-config-override* ~opts (fn [] ~@body)))

  (alter-meta! assoc :arglists '([{:keys [switches secret-keys] :as opts-override} & body])))
