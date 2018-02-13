(ns nomad.config
  (:require [buddy.core.codecs :as bc]
            [buddy.core.crypto :as b]
            [buddy.core.nonce :as bn]
            [clojure.set :as set]
            [clojure.tools.reader.edn :as edn]
            [clojure.string :as s]
            [clojure.walk :as w]))

(defrecord Secret [key-id cipher-text])

(defn generate-key []
  (bc/bytes->hex (bn/random-bytes 32)))

(def ^:private block-size
  (b/block-size (b/block-cipher :aes :cbc)))

(defn decrypt [cipher-text secret-key]
  (let [[iv cipher-bytes] (map byte-array (split-at block-size (bc/hex->bytes cipher-text)))]
    (edn/read-string (b/decrypt cipher-bytes (bc/hex->bytes secret-key) iv))))

(defn encrypt [plain-obj secret-key]
  (let [iv (bn/random-bytes block-size)]
    (str (bc/bytes->hex iv)
         (bc/bytes->hex (b/encrypt (bc/str->bytes (pr-str plain-obj)) (bc/hex->bytes secret-key) iv)))))

(defn- apply-secrets [config secret-keys]
  (w/postwalk (fn [v]
                (if (instance? Secret v)
                  (let [{:keys [key-id cipher-text]} v]
                    (if-let [secret-key (get secret-keys key-id)]
                      (decrypt cipher-text secret-key)
                      (throw (ex-info "missing secret-key" {:key-id key-id}))))

                  v))
              config))

(def ^:dynamic *secret-keys* {})

(defn secret
  ([key-id cipher-text]
   (secret *secret-keys* key-id cipher-text))
  ([secret-keys key-id cipher-text]
   (if-let [secret-key (get secret-keys key-id)]
     (decrypt cipher-text secret-key)
     (throw (ex-info "missing secret-key" {:key-id key-id})))))

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

(def ^:dynamic *switches* #{})

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
  `(switch* *switches* ~@clauses))

(defn mk-config [f]
  (-> (fn [{:keys [switches secret-keys]}]
        (binding [*switches* switches
                  *secret-keys* secret-keys]
          (f)))
      memoize))

(defmacro defconfig [name config]
  `(def ~(-> name (with-meta {:arglists ''([] [{:keys [switches secret-keys]}])}))
     (let [config# (mk-config (fn [] ~config))]
       (fn
         ([] (config# {:switches *switches*, :secret-keys *secret-keys*}))
         ([opts#] (config# opts#))))))

(defn- parse-switch [switch]
  (if-let [[_ switch-ns switch-name] (re-matches #"(.+?)/(.+)" switch)]
    (keyword switch-ns switch-name)
    (keyword switch)))

(def env-switches
  (-> (or (System/getenv "NOMAD_SWITCHES")
          (System/getProperty "nomad.switches"))
      (some-> (s/split #","))
      (->> (into [] (map parse-switch)))))

(defn set-default-switches! [switches]
  (when switches
    (alter-var-root #'*switches* (constantly switches))))


(defn set-default-secret-keys! [secret-keys]
  (when secret-keys
    (alter-var-root #'*secret-keys* (constantly secret-keys))))
