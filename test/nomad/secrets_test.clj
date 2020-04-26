(ns nomad.secrets-test
  (:require [nomad.secrets :as sut]
            [clojure.test :as t]))

(def secret-key
  "n0ZUdKFVOEulRodqekbucCxB/CSVu/Qw0aEzMReKEcE=")

(t/deftest resolves-secrets
  (t/is (= (let [cypher-text (sut/encrypt secret-key "password123")]
             (-> (sut/with-secret-keys {:my-key secret-key}
                   {:db-config {:password (sut/decrypt :my-key cypher-text)}})
                 (get-in [:db-config :password])))
           "password123")))
