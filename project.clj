(defproject jarohen/nomad "0.7.1"
  :description "A Clojure library to allow Clojure applications to define and access host/instance-specific configuration"
  :url "https://github.com/james-henderson/nomad.git"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.7.0-RC1"]
                 [org.clojure/tools.reader "0.9.2"]
                 [org.clojure/tools.logging "0.3.1"]

                 [camel-snake-kebab "0.3.1"]
                 [medley "0.6.0"]
                 [prismatic/schema "0.4.3"]

                 [buddy/buddy-core "0.5.0"]])
