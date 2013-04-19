(ns nomad
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]))

(defprotocol ConfigFile
  (etag [_])
  (exists? [_]))

(extend-protocol ConfigFile
  java.io.File
  (etag [f] (.lastModified f))
  (exists? [f] (.exists f))

  java.net.URL
  (etag [url]
    (case (.getProtocol url)
      "file" (etag (io/as-file url))

      ;; otherwise, we presume the config file is read-only
      ;; (i.e. in a JAR file)
      url))
  (exists? [url]
    ;; any offers of a better implementation for this?
    (try
      (with-open [s (.openStream url)]
        true)
      (catch Exception e
        false)))

  nil
  (etag [_] nil)
  (exists? [_] false))

(defn- load-config [config-file]
  (when (exists? config-file)
    (with-meta (read-string (slurp config-file))
      {:etag (etag config-file)})))

(defn get-config [config-ref config-file]
  (dosync
   (alter config-ref
          (fn [current-config]
            (if-not current-config
              (load-config config-file)
              
              (let [file-etag (etag config-file)]
                (if (not= file-etag (-> current-config meta :etag))
                  (load-config config-file)
                  current-config)))))))

(def ^:private get-hostname
  (memoize
   (fn []
     (.trim (:out (sh "hostname"))))))

(defn get-host-config [config]
  (get-in config [:nomad/hosts (get-hostname)]))

(def ^:private get-instance
  (memoize
   (fn []
     (get (System/getenv) "NOMAD_INSTANCE" :default))))

(defn get-instance-config [config]
  (get-in (get-host-config config) [:nomad/instances (get-instance)]))

(defmacro defconfig [name file-or-resource]
  `(let [config-ref# (ref nil)]
     (defn ~name []
       (get-config config-ref# ~file-or-resource))))
