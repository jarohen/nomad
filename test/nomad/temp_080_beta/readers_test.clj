(ns nomad.temp-080-beta.readers-test
  (:require [nomad.temp-080-beta.readers :refer :all]
            [nomad.temp-080-beta.references :as nr]
            [nomad.temp-080-beta.secret :as ns]
            [clojure.test :refer :all]))

(deftest reads-secret
  (let [secret-key (ns/generate-key)
        plain-text "Foo McBar"
        reader (secret-reader [:test (ns/encrypt plain-text secret-key)])]
    (is (= plain-text)
        (nr/resolve-value reader {:nomad/secret-keys {:test secret-key}}))))
