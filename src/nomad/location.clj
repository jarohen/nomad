(ns nomad.location
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as s]
            [schema.core :as sc]))

(def Location
  {(sc/optional-key :environment) (sc/maybe sc/Str)
   (sc/optional-key :host) (sc/maybe sc/Str)
   (sc/optional-key :user) (sc/maybe sc/Str)
   (sc/optional-key :instance) (sc/maybe sc/Str)})

(sc/defn get-location :- Location []
  (let [host (s/trim (:out (sh "hostname")))]
    {:environment (or (get (System/getenv) "NOMAD_ENV")
                      (System/getProperty "nomad.env"))

     ;; not sure how I plan to make this work on Windoze... Will see if
     ;; someone complains first, I suspect. If you do see this, I'm
     ;; generally quite quick at merging PRs ;)
     :host host
     :host-instance [host
                     (or (get (System/getenv) "NOMAD_INSTANCE")
                         (System/getProperty "nomad.instance"))]

     :user (System/getProperty "user.name")}))
