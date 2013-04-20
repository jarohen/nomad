(ns nomad
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]))

(def ^:private get-hostname
  (memoize
   (fn []
     (.trim (:out (sh "hostname"))))))

(def ^:private get-instance
  (memoize
   (fn []
     (get (System/getenv) "NOMAD_INSTANCE" :default))))

(defprotocol ^:private ConfigFile
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

  ;; Most people will use (io/resource ...) to get the URL - which
  ;; returns nil if the resource doesn't exist. So if they do manually
  ;; specify a URL that doesn't exist, I'm happy with this throwing an
  ;; exception in load-config rather than returning nil.
  (exists? [url] true)

  nil
  (etag [_] nil)
  (exists? [_] false))

(defn- with-current-host-config [config]
  (if-let [host-config (get-in config [:nomad/hosts (get-hostname)])]
    (assoc config :nomad/current-host host-config)
    config))

(defn- with-current-instance-config [config]
  (if-let [instance-config (get-in config [:nomad/current-host
                                           :nomad/instances
                                           (get-instance)])]
    (assoc config :nomad/current-instance instance-config)
    config))

(defn- load-config [config-file]
  (when (exists? config-file)
    (-> (with-meta (read-string (slurp config-file))
          {:etag (etag config-file)
           :config-file config-file})
        with-current-host-config
        with-current-instance-config)))

(defn- get-current-config [config-ref config-file]
  (dosync
   (alter config-ref
          (fn [current-config]
            (if-not current-config
              (load-config config-file)
              
              (let [file-etag (etag config-file)]
                (if (not= file-etag (-> current-config meta :etag))
                  (load-config config-file)
                  current-config)))))))

(defmacro defconfig [name file-or-resource]
  `(let [config-ref# (ref nil)]
     (defn ~name []
       (#'get-current-config config-ref# ~file-or-resource))))
