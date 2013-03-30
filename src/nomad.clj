(ns nomad
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]))

(defn- config-file []
  (io/file (io/resource "nomad-config.edn")))

(defn- load-config []
  (let [file (config-file)]
    (when (and file (.exists file))
      (with-meta (read-string (slurp file))
        {:as-of (.lastModified file)}))))

(def ^:private config (ref nil))

(defn get-config []
  (dosync
   (alter config
          (fn [current-config]
            (if-not current-config
              (load-config)
              
              (let [file-mod (.lastModified (config-file))]
                (if (not= file-mod (-> current-config meta :as-of))
                  (load-config)
                    current-config)))))))

(def ^:private get-hostname
  (memoize
   (fn []
     (.trim (:out (sh "hostname"))))))

(defn get-host-config []
  (get-in (get-config) [:hosts (get-hostname)]))

(def ^:private get-instance
  (memoize
   (fn []
     (get (System/getenv) "NOMAD_INSTANCE" :default))))

(defn get-instance-config []
  (get-in (get-host-config) [:instances (get-instance)]))