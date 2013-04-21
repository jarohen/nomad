(ns nomad
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [nomad.map :refer [deep-merge]]))

(def ^:private get-hostname
  (memoize
   (fn []
     (.trim (:out (sh "hostname"))))))

(def ^:private get-instance
  (memoize
   (fn []
     (get (System/getenv) "NOMAD_INSTANCE" :default))))

(defprotocol ConfigFile
  (etag [_])
  (slurp* [_]))

(extend-protocol ConfigFile
  java.io.File
  (etag [f] {:file f
             :last-mod (.lastModified f)})
  (slurp* [f] (slurp f))

  java.net.URL
  (etag [url]
    (case (.getProtocol url)
      "file" (etag (io/as-file url))

      ;; otherwise, we presume the config file is read-only
      ;; (i.e. in a JAR file)
      {:url url}))

  (slurp* [url] (slurp url))

  nil
  (etag [_] nil)
  (slurp* [_] (pr-str {})))

(defn- reload-config-file [config-file]
  (binding [*data-readers* (assoc *data-readers*
                             'nomad/file io/file)]
    (with-meta (read-string (slurp* config-file))
      {:old-etag (etag config-file)
       :config-file config-file})))

(defn- update-config-file [current-config]
  (let [{:keys [old-etag config-file]} (meta current-config)
        new-etag (etag config-file)]
    (if (not= old-etag new-etag)
      (vary-meta (reload-config-file config-file) assoc :updated? true)
      (vary-meta current-config dissoc :updated?))))

(defn- update-config-pair [current-config]
  (let [{:keys [old-public old-private]} (meta current-config)
        new-public (when old-public
                     (update-config-file old-public))
        new-private (or (when-let [new-private-file (-> new-public :nomad/private-file)]
                          (update-config-file (vary-meta (or old-private {})
                                                         assoc :config-file new-private-file)))
                        {})]
    (with-meta (if (or (-> new-public meta :updated?)
                       (-> new-private meta :updated?))
                 (deep-merge new-public new-private)
                 current-config)
      {:old-public new-public
       :old-private new-private})))

(defn- with-current-specific-config [config config-key refresh-specific-config]
  (update-in config
             [config-key]
             (fn [current-config]
               (update-config-pair
                (or current-config
                    (when-let [public-config (refresh-specific-config)]
                      (with-meta public-config
                        {:old-public public-config}))
                    {})))))

(defn- with-current-host-config [config]
  (let [hostname (get-hostname)]
    (-> config
        (with-current-specific-config :nomad/current-host
          #(get-in config [:nomad/hosts hostname]))
        (assoc-in [:nomad/current-host :nomad/hostname] hostname))))

(defn- with-current-instance-config [config]
  (let [instance (get-instance)]
    (-> config
        (with-current-specific-config :nomad/current-instance
          #(get-in config [:nomad/current-host :nomad/instances instance]))
        (assoc-in [:nomad/current-instance :nomad/instance] instance))))

(defn- update-config [current-config]
  (-> (update-config-pair current-config)
      with-current-host-config
      with-current-instance-config))

(defn- get-current-config [config-ref]
  (dosync (alter config-ref update-config)))

(defmacro defconfig [name file-or-resource]
  `(let [config-ref# (ref (with-meta {}
                            {:old-public (with-meta {}
                                           {:config-file ~file-or-resource})}))]
     (defn ~name []
       (#'get-current-config config-ref#))))
