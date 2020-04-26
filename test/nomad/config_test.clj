(ns nomad.config-test
  (:require [nomad.config :as sut]
            [clojure.test :as t]))

(t/deftest applies-switches
  (let [opts {:switches #{:the-switch :other-switch}}]
    (t/is (= (sut/switch* opts
               :the-switch true
               false)
             true))
    (t/is (= (sut/switch* opts
               :other-switch true
               :the-switch false)
             true))
    (t/is (nil? (sut/switch* opts
                  :not-this-one true)))))
