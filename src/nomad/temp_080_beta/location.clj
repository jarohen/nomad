(ns nomad.temp-080-beta.location
  (:require [nomad.temp-080-beta.merge :refer [deep-merge]]
            [medley.core :as m]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as s]
            [schema.core :as sc]))

(def Location
  {(sc/optional-key :environment) (sc/maybe sc/Str)
   (sc/optional-key :host) (sc/maybe sc/Str)
   (sc/optional-key :user) (sc/maybe sc/Str)
   (sc/optional-key :instance) (sc/maybe sc/Str)})

(sc/defn get-location :- Location []
  {:environment (or (get (System/getenv) "NOMAD_ENV")
                    (System/getProperty "nomad.env"))

   :instance (or (get (System/getenv) "NOMAD_INSTANCE")
                 (System/getProperty "nomad.instance"))

   ;; not sure how I plan to make this work on Windoze... Will see if
   ;; someone complains first, I suspect. If you do see this, I'm
   ;; generally quite quick at merging PRs ;)
   :host (s/trim (:out (sh "hostname")))

   :user (System/getProperty "user.name")})

(sc/defn select-location [config {:keys [environment host user instance] :as location} :- (sc/maybe Location)]
  {:general (dissoc config :nomad/environments :nomad/hosts)

   :host (-> (get-in config [:nomad/hosts (or host :default)])
             (dissoc :nomad/users :nomad/instances))

   :instance (get-in config [:nomad/hosts (or host :default)
                             :nomad/instances (or instance :default)])

   :user (get-in config [:nomad/hosts (or host :default)
                         :nomad/users (or user :default)])

   :environment (get-in config [:nomad/environments (or environment :default)])})
