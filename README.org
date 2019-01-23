* nomad

A configuration library designed to allow Clojure applications to travel
painlessly between different hosts and environments.

** Usage

*** Set-up

Add the *nomad* dependency to your =project.clj=

#+BEGIN_SRC clojure
  [jarohen/nomad "0.9.0-rc1"]
#+END_SRC

Please see the Changelog for more details.

*** Rationale

In an ideal world, we'd choose to declare configuration as a vanilla Clojure
map, and access it as such, from anywhere, and everything would Just Work™.

Nomad aims to get as close to that as possible. In doing so, it makes a few
opinionated decisions:

- Configuration is declared *near* the code it is configuring - i.e. not in an
  EDN file, not in environment variables, not in a separate infrastructure
  repository, not in deploy scripts, etc etc etc. This locality allows us to
  *reason* about how the code will behave in certain environments without having
  to audit an entire system, and aids us when we come to adding new
  configuration variables.

  Sure, we'll always need to allow whatever's bootstrapping our
  application to alter the behaviour in some way (Nomad relies on passing in a
  set of 'switches', for example), but let's *minimise* it.

- Configuration is declared in *Clojure* - this gives us the full flexibility of
  normal Clojure functions to build/compose our configuration as necessary. In
  our experience, configuration libraries often tend to try to replicate a full
  language trying to emulate certain behaviours - retrieving configuration from
  elsewhere, fallbacks/defaults, environment variable parsing and composing
  multiple configuration files, to name a few.

  Let's just use Clojure core/simple Java interop over a config-specific DSL -
  they're good at this.

  Is there a possibility of this freedom getting abused, and the boundary
  between 'configuration' and code getting blurred? Sure. I'm trusting you to
  know when your configuration goes significantly beyond 'just a map', though.

*** Migrating from 0.7.x/0.8.x betas

I had a significant re-think of what I wanted from a configuration library
between 0.7.x/0.8.x and 0.9.x, based on learnings from using it in a number of
non-trivial applications.

- The original 0.7.x behaviour will be maintained for the time being, in the
  original =nomad= namespace, although will be removed in a later release.
- The 0.8.x behaviour never made it to a stable release, and so has been
  removed in 0.9.0-rc1.

There is a migration guide for both of these versions in the changelog.

** Getting started

First, we require =[nomad.config :as n]=.

In the entry point to our application (or, for now, at the REPL) we need to
initialise Nomad - over time, we'll need to add to this:

#+BEGIN_SRC clojure
  (n/set-defaults! {})
#+END_SRC

We then use =defconfig= to declare some configuration:

#+BEGIN_SRC clojure
  (n/defconfig email-config
    {:email-behaviour :just-console-for-now})

  (defn email-user! [{:keys [...] :as email}]
    (case (:email-behaviour email-config)
      :just-console-for-now (prn "Would send:" email)
      :actually-send-the-email (send-email! email)))
#+END_SRC

We can see that, once we've declared our configuration, we use it in the same
way we'd use any other vanilla Clojure data structure. We can destructure it,
compose it, pass it around, play with it/redefine it in our REPL - no problem.

If I wanted to, I could use =System/getenv=, =System/getProperty=, or any
vanilla Clojure functions etc in here:

#+BEGIN_SRC clojure
  (n/defconfig email-config
    {:behaviour :actually-send-the-email
     :host (System/getenv "EMAIL_HOST")
     :port (or (some-> (System/getenv "EMAIL_PORT") Long/parseLong)
               25)})
#+END_SRC

This obviates the need for any kind of config DSL to retrieve/parse/default
configuration values.

** Changing configuration based on location

Your configuration will likely vary depending on whether you're running your
application in development, test/beta/staging environments, or production. Nomad
accomplishes this using 'switches', which are set in your call to
=set-defaults!=:

#+BEGIN_SRC clojure
  (n/set-defaults! {:switches #{:live}})
#+END_SRC

You can then vary your configuration using the =n/switch= macro, which behaves
a lot like Clojure's =case= macro:

#+BEGIN_SRC clojure
  ;; in your app entry point
  (n/set-defaults! {:switches #{:live}})

  ;; in your namespace
  (n/defconfig db-config
    (merge {:port 5432}
           (n/switch
             :beta {:host "beta-db-host"
                    :username "beta-username"}
             :live {:host "live-db-host"
                    :username "live-username"}

             ;; you can also provide a default, if none of the above switches are
             ;; active
             {:host "localhost"
              :username "local-user"})))

  ;; at the REPL (say)
  (let [{:keys [host port username]} db-config]
    ;; in here, we get the live config, because of our earlier `set-defaults!`
    ...)
#+END_SRC

You're free to choose how to select your switches - or, you can use
=n/env-switches=, which looks for the =NOMAD_SWITCHES= environment variable, or
the =nomad.switches= JVM property, expecting a comma-separated list of switches:

#+BEGIN_SRC clojure
  ;; starting the application
  NOMAD_SWITCHES=live,foo java -cp ... clojure.main -m ...

  ;; --- in the entry point
  (n/set-defaults! {:switches n/env-switches})
  ;; sets switches to #{:live :foo}
#+END_SRC

** Secrets (shh!)

Nomad can manage your secrets for you, too. Under Nomad, these are encrypted and
checked in to your application repository, with the encryption keys managed
outside of your application (in whatever manner you choose).

First, generate yourself an encryption key using =(n/generate-key)=

#+BEGIN_SRC clojure
  (nomad.config/generate-key)
  ;; => "tvuGp8oGGbP+IQSzidYS+oXB3fhGZLpVLhMFljL0I/o="
#+END_SRC

We then pass this to Nomad as part of the call to =set-defaults!=:

#+BEGIN_SRC clojure
  (n/set-defaults! {:secret-keys {:my-dev-key "tvuGp8oGGbP+IQSzidYS+oXB3fhGZLpVLhMFljL0I/o="}})
#+END_SRC

Obviously, normally, this would not be checked into your application repository!
You can get it from an environment variable, an out-of-band file on the local
disk, some external infrastructure management, some cloud key manager, or
something else entirely - take your pick!

We then encrypt credentials using =n/encrypt=, and store this cipher-text, along
with the key-id used to encrypt the credentials, in our =defconfig=
declarations:

#+BEGIN_SRC clojure
  ;; --- at your REPL

  (n/encrypt :my-dev-key "super-secure-password123")
  ;; => "y/DwItK86ZgtUUTzz+sDCNd3rpsOuiyKmqcHIelHnRdrpr06k43NEnrraWrfUHE39ZXtLItqxZVM3hmCj1pqLw=="

  ;; --- in your namespace
  (defconfig db-config
    {:host "db-host"
     :username "db-username"
     :password (n/decrypt :my-dev-key "y/DwItK86ZgtUUTzz+sDCNd3rpsOuiyKmqcHIelHnRdrpr06k43NEnrraWrfUHE39ZXtLItqxZVM3hmCj1pqLw==")})

  ;; access the password like any other map key
  (let [{:keys [host username password]} db-config]
    ...)
#+END_SRC

** Testing your configuration

Given configuration declarations are just normal Clojure variables, you can
experiment with them at the REPL, as you would any other Clojure data structure.

Nomad does offer a couple of other tools to facilitate testing, though. First,
=defconfig= declarations can be dynamically re-bound, using Clojure's standard
=binding= macro:

#+BEGIN_SRC clojure
  (n/defconfig email-config
    {:email-behaviour :just-console-for-now})

  (defn email-user! [{:keys [...] :as email}]
    (case (:email-behaviour email-config)
      :just-console-for-now (prn "Would send:" email)
      :actually-send-the-email (send-email! email)))

  (email-user! {...})
  ;; prints the email to the console

  (binding [email-config {:email-behaviour :actually-send-the-email}]
    (email-user! {...}))
  ;; actually sends the email
#+END_SRC

Nomad also offers a =with-config-override= macro, which allows you to override
what switches are active, throughout your system, for the duration of the
expression body:

#+BEGIN_SRC clojure
  (n/defconfig email-config
    {:email-behaviour (n/switch
                        :live :actually-send-the-email
                        :just-console-for-now)})

  (defn email-user! [{:keys [...] :as email}]
    (case (:email-behaviour email-config)
      :just-console-for-now (prn "Would send:" email)
      :actually-send-the-email (send-email! email)))

  (email-user! {...})
  ;; prints the email to the console

  (n/with-config-override {:switches #{:live}}
    (email-user! {...}))
  ;; actually sends the email
#+END_SRC


** Bugs/features/suggestions/questions?

Please feel free to submit bug reports/patches etc through the GitHub
repository in the usual way!

Thanks!

** Changes

The Nomad changelog has moved to CHANGES.org.

** License

Copyright © 2013-2018 James Henderson

Distributed under the Eclipse Public License, the same as Clojure.
