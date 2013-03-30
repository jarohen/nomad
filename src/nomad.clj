(ns nomad
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]))

(def ^:dynamic *config-file*
  (io/file (io/resource "nomad-config.edn")))

(defn load-config []
  (with-meta (read-string (slurp *config-file*))
    {:as-of (.lastModified *config-file*)}))

(def ^:dynamic *config* (ref nil))

(defn latest-config []
  (dosync
   (alter *config*
          (fn [current-config]
            (if-not current-config
              (load-config)
              
              (let [file-mod (.lastModified *config-file*)]
                (if (not= file-mod (-> current-config meta :as-of))
                  (load-config)
                    current-config)))))))

(def ^:private get-hostname
  (memoize
   (fn []
     (.trim (:out (sh "hostname"))))))

(defn get-host-config []
  (get-in (latest-config) [:hosts (get-hostname)]))