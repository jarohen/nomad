(ns nomad.temp-080-beta.secret-test
  (:require [nomad.temp-080-beta.secret :refer :all]
            [clojure.test :refer :all]))

(deftest round-trip
  (let [plain-text "hello world!"
        secret-key (generate-key)]
    (is (= plain-text
           (-> plain-text
               (encrypt secret-key)
               (decrypt secret-key))))))
