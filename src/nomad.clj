(ns nomad
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [nomad.map :refer [deep-merge]]
            [clojure.tools.reader.edn :as edn]
            [clojure.walk :refer [postwalk-replace]]))

(defprotocol ConfigFile
  (etag [_])
  (slurp* [_]))

(defn- safe-slurp [f default]
  (try
    (slurp f)
    (catch Exception e
      default)))

(extend-protocol ConfigFile
  java.io.File
  (etag [f] {:file  f
             :last-mod (.lastModified f)})
  (slurp* [f] (safe-slurp f (pr-str {})))

  java.net.URL
  (etag [url]
    (case (.getProtocol url)
      "file" (etag (io/as-file url))

      ;; otherwise, we presume the config file is read-only
      ;; (i.e. in a JAR file)
      {:url url}))
  (slurp* [url] (safe-slurp url (pr-str {})))

  nil
  (etag [_] nil)
  (slurp* [_] (pr-str {}))

  java.lang.String
  (etag [s] s)
  (slurp* [s] s))

(defn- get-hostname []
  (.trim (:out (sh "hostname"))))

(defn- get-instance []
  (get (System/getenv) "NOMAD_INSTANCE" :default))

(defn- get-environment []
  (or (System/getProperty "nomad.env")
      (get (System/getenv) "NOMAD_ENV")
      :default))

(defn- read-edn-env-var [env-var]
  (let [val-str (System/getenv env-var)]
    (or
     (try
       (edn/read-string val-str)
       (catch Throwable e
         (throw (ex-info "Can't read-string edn-env-var:"
                         {:env-var env-var
                          :val-str val-str}))))

     ;; This does return :nomad/nil when the env-var is literal
     ;; nil (i.e. VAR=nil lein repl) but not sure I can fix this
     ;; until tools.reader accepts nil as a return value from a
     ;; reader macro fn
     :nomad/nil)))

(defn- nomad-data-readers [snippet-reader]
  {'nomad/file io/file
   'nomad/snippet snippet-reader
   'nomad/env-var #(or (System/getenv %) :nomad/nil)
   'nomad/edn-env-var read-edn-env-var})

(defn- replace-nomad-nils [m]
  (postwalk-replace {:nomad/nil nil} m))

(defn- readers-without-snippets []
  {:readers (nomad-data-readers (constantly ::snippet))})

(defn- readers-with-snippets [snippets]
  {:readers (nomad-data-readers
             (fn [ks]
               (or
                (get-in snippets ks)
                (throw (ex-info "No snippet found for keys" {:keys ks})))))})

(defn- reload-config-file [config-file]
  (let [config-str (slurp* config-file)
        without-snippets (edn/read-string (readers-without-snippets) config-str)
        snippets (get without-snippets :nomad/snippets)
        with-snippets (-> (edn/read-string (readers-with-snippets snippets)
                                           config-str)
                          (dissoc :nomad/snippets)
                          replace-nomad-nils)]
    {:etag  (etag config-file)
     :config-file config-file
     :config  with-snippets}))

(defn- update-config-file [current-config config-file]
  (let [{old-etag :etag} current-config
        new-etag (etag config-file)]
    (if (not= old-etag new-etag)
      (reload-config-file config-file)
      current-config)))

(defn- update-specific-config [current-config downstream-key upstream-key selector value]
  (let [{new-etag :etag
         new-upstream-config :config} (get current-config upstream-key)

         {old-etag :upstream-etag
          old-selector-value :selector-value
          :as current-downstream-config} (get current-config downstream-key)]
    (assoc current-config
      downstream-key (if (and (= new-etag old-etag)
                              (= old-selector-value value))
                       current-downstream-config
                       {:upstream-etag new-etag
                        :etag new-etag
                        :selector-value value
                        :config (get-in new-upstream-config [selector value])}))))

(defn- add-location [configs]
  (assoc configs
    :location {:nomad/environment (get-environment)
               :nomad/hostname (get-hostname)
               :nomad/instance (get-instance)}))

(defn- update-private-config [configs src-key dest-key]
  (let [{old-public-etag :public-etag
         :as current-config} (get configs dest-key)
         
        {new-public-etag :etag} (get configs src-key)
        private-file (get-in configs [src-key :config :nomad/private-file])]
    (assoc configs
      dest-key (if (not= old-public-etag new-public-etag)
                 (reload-config-file private-file)
                 (update-config-file current-config private-file)))))

(defn- merge-configs [configs]
  (-> (deep-merge (or (get-in configs [:general :config]) {})
                  (or (get-in configs [:general-private :config]) {})
                  (or (get-in configs [:host :config]) {})
                  (or (get-in configs [:host-private :config]) {})
                  (or (get-in configs [:environment :config]) {})
                  (or (get-in configs [:environment-private :config]) {})
                  (or (get-in configs [:instance :config]) {})
                  (or (get-in configs [:instance-private :config]) {})
                  (or (get-in configs [:location]) {}))
      (dissoc :nomad/hosts :nomad/instances :nomad/environments :nomad/private-file)
      (with-meta configs)))

(defn- update-config [current-config]
  (-> current-config
      (update-in [:general] update-config-file (get-in current-config [:general :config-file]))
      (update-specific-config :environment :general :nomad/environments (get-environment))
      (update-specific-config :host :general :nomad/hosts (get-hostname))
      (update-specific-config :instance :host :nomad/instances (get-instance))
      add-location
      (update-private-config :general :general-private)
      (update-private-config :environment :environment-private)
      (update-private-config :host :host-private)
      (update-private-config :instance :instance-private)))

;; ---------- PUBLIC API ----------

(defn read-config [file-or-resource & [{:keys [cached-config]}]]
  (let [config-map (or (meta cached-config)
                       {:general {:config-file file-or-resource}})
        updated-config (update-config config-map)]
    (merge-configs updated-config)))

(defmacro with-location-override [override & body]
  (let [[override-type override-value] (first override)
        override-fn-name (str "nomad/get-" (name override-type))
        override-fn-sym (symbol override-fn-name)]
    `(with-redefs [~override-fn-sym (constantly ~override-value)]
       ~@body)))

(defmacro defconfig [name file-or-resource]
  `(let [!cached-config# (atom nil)]
     (defn ~name []
       (swap! !cached-config#
              (fn [cached-config#]
                (read-config ~file-or-resource
                             {:cached-config cached-config#}))))))


