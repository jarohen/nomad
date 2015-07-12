(ns nomad.readers
  (:require [nomad.references :refer [resolve-references ResolvableReference]]
            [nomad.merge :refer [deep-merge]]
            [nomad.secret :as ns]
            [camel-snake-kebab.core :as csk]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.tools.logging :as log]
            [clojure.tools.reader.edn :as edn]))

(def file-reader
  (comp io/file #(s/replace % #"^~" (System/getProperty "user.home"))))

(def resource-reader
  (some-fn io/resource

           (fn [path]
             (log/warnf "Can't read resource: '%s', ignoring..." path)
             ::invalid-include)))

(defn try-read-edn [s handle-error]
  (try
    (when s
      (edn/read-string s))
    (catch Exception e
      (handle-error))))

(defrecord NullableReference [f]
  ResolvableReference
  (resolve-value [_ config]
    (f)))

(defn try-read-edn [s handle-error]
  (try
    (when s
      (edn/read-string s))
    (catch Exception e
      (handle-error))))

(defn read-env-var [env-var default]
  (-> (or (System/getenv (csk/->SCREAMING_SNAKE_CASE_STRING env-var)) default)))

(defn env-var-reader [[env-var default]]
  (->NullableReference #(read-env-var env-var default)))

(defn edn-env-var-reader [[env-var default]]
  (->NullableReference (fn []
                         (let [env-value (read-env-var env-var default)]
                           (try-read-edn env-value #(throw (ex-info "Phoenix: failed reading edn-env-var"
                                                                    {:env-var env-var
                                                                     :value env-value})))))))
(defn read-jvm-prop [prop-name default]
  (System/getProperty (name prop-name) default))

(defn jvm-prop-reader [[jvm-prop default]]
  (->NullableReference #(read-jvm-prop jvm-prop default)))

(defn edn-jvm-prop-reader [[jvm-prop default]]
  (->NullableReference (fn []
                         (let [env-value (read-jvm-prop jvm-prop default)]
                           (try-read-edn env-value #(throw (ex-info "Phoenix: failed reading edn-jvm-prop"
                                                                    {:jvm-prop jvm-prop
                                                                     :value env-value})))))))
(defn secret-reader [[secret-key-name cypher-text]]
  (reify ResolvableReference
    (resolve-value [_ {:keys [:nomad/secret-keys] :as config}]
      (let [secret-key (get secret-keys secret-key-name)]
        (assert secret-key (format "Nomad: can't find secret key '%s'" secret-key-name))

        (ns/decrypt cypher-text (get secret-keys secret-key-name))))))

(defn snippet-reader [snippet-key]
  (reify ResolvableReference
    (resolve-value [_ {:keys [:nomad/snippets] :as config}]
      (get snippets snippet-key))))

(defrecord LocationReference [location-key config-map]
  ResolvableReference
  (resolve-value [_ config]
    (deep-merge [(:default config-map)
                 (get config-map (get-in (meta config) [:nomad/location location-key]))])))

(defn by-environment-reader [env-map]
  (map->LocationReference {:location-key :environment
                           :config-map env-map}))

(defn by-host-reader [env-map]
  (map->LocationReference {:location-key :host
                           :config-map env-map}))

(defn by-host-instance-reader [env-map]
  (map->LocationReference {:location-key :host-instance
                           :config-map env-map}))

(defn by-user-reader [env-map]
  (map->LocationReference {:location-key :user
                           :config-map env-map}))
