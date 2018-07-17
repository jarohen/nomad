(ns nomad.config-test
  (:require [nomad.config :as sut]
            [clojure.test :as t]))

(t/deftest applies-switches
  (t/is (= (#'sut/apply-switches {:config-key :value

                                  :switched? (sut/switch
                                               :the-switch true
                                               false)

                                  :overruled? (sut/switch
                                                :other-switch true
                                                :the-switch false)

                                  :uh-oh? (sut/switch
                                            :not-this-one true)}

                                 #{:the-switch :other-switch})
           {:config-key :value
            :switched? true
            :overruled? true
            :uh-oh? nil})))

(t/deftest applies-keyed-switches
  (t/is (= (#'sut/apply-switches {:keyed-switch (sut/switch
                                                  :live :not-this-one)}
                                 #{:keyed/live})
           {:keyed-switch nil}))

  (t/is (= (#'sut/apply-switches {:keyed-switch (sut/switch
                                                  :live :yeehah)
                                  :nomad/key :keyed}
                                 #{:keyed/live})

           {:keyed-switch :yeehah})))

(t/deftest resolves-secrets
  (let [secret-key (sut/generate-key)]
    (t/is (= (-> (sut/resolve-config {:db-config {:password (sut/->Secret :my-key (sut/encrypt "password123" secret-key))}}
                                     {:secret-keys {:my-key secret-key}})
                 (get-in [:db-config :password]))
             "password123"))))
