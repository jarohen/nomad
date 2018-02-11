(ns nomad.config
  (:require [buddy.core.codecs :as bc]
            [buddy.core.crypto :as b]
            [buddy.core.nonce :as bn]
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

(comment
  (let [foo-key (generate-key)]
    (-> {:a 1 :b 2}
        (encrypt foo-key)
        (doto prn)
        (decrypt foo-key))))

(defmacro switch
  "Takes a set of switch/expr clauses, and an optional default value.
  Returns the configuration from the first active switch, or the default if none are active, or nil.

  (w/switch
    <switch> <expr>
    <switch-2> <expr-2>
    ...
    <default-expr>)"
  {:style/indent 0}
  [& clauses]

  `(-> (fn [switches#]
         (condp #(contains? %2 %1) switches#
           ~@clauses
           ~@(when (zero? (mod (count clauses) 2))
               [nil])))
       (with-meta {::switch? true})))

(defn- apply-switches [{k :wiring/key, :as config} switches]
  (w/postwalk (fn [v]
                (if (::switch? (meta v))
                  (v switches)
                  v))
              config))

(defn- parse-switch [switch]
  (if-let [[_ switch-ns switch-name] (re-matches #"(.+?)/(.+)" switch)]
    (keyword switch-ns switch-name)
    (keyword switch)))

(def env-switches
  (-> (or (System/getenv "NOMAD_SWITCHES")
          (System/getProperty "nomad.switches"))
      (some-> (s/split #","))
      (->> (into [] (map parse-switch)))))

(defn resolve-config
  ([config] (resolve-config config {}))

  ([config {:keys [switches secret-keys], :or {switches env-switches}}]
   (-> config
       (apply-switches switches)
       (apply-secrets secret-keys))))
