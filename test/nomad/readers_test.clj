(ns nomad.readers-test
  (:require [nomad.readers :refer :all]
            [nomad.references :as nr]
            [nomad.secret :as ns]
            [clojure.test :refer :all]))

(deftest reads-secret
  (let [secret-key (ns/generate-key)
        plain-text "Foo McBar"
        reader (secret-reader [:test (ns/encrypt plain-text secret-key)])]
    (is (= plain-text)
        (nr/resolve-value reader {:nomad/secret-keys {:test secret-key}}))))
