# nomad

A Clojure library designed to allow Clojure configuration to travel
between hosts.

You can use Nomad to define and access host-specific configuration,
which can be saved and tracked through your usual version control
system. For example, when you're developing a web application, you may
want the web port to be different between development and production
instances, or you may want to send out e-mails to clients (or not!)
depending on the host that the application is running on.

While this does sound an easy thing to do, I have found myself coding
this in many different projects, so it was time to turn it into a
separate dependency!

## Usage

### Set-up

Add the **nomad** dependency to your `project.clj`

```clojure
[jarohen/nomad "0.2.0"]

;; legacy
[jarohen/nomad "0.1.0"]

;; Version 0.2.0 has introduced a couple of breaking changes
;; since version 0.1.0 - please see 'Changes', below.

```


Nomad expects your configuration to be stored in an [EDN][1]
file. Nomad does expect a particular structure for your configuration,
however it will load any data structure in the file.

[1]: https://github.com/edn-format/edn

To load the data structure in the file, use the `defconfig` macro,
passing in either a file or a classpath resource:

nomad-config.edn:

```clojure
{:my-key "my-value"}
```

my_ns.clj:

```clojure
(ns my-ns
    (:require [nomad :refer [defconfig]
              [clojure.java.io :as io]]))

(defconfig my-config (io/resource "config/my-config.edn"))

(my-config)
;; -> {:my-key "my-value"}
```

### Caching

Nomad will cache the configuration where possible, but will
auto-reload the configuration if the underlying file is modified.

### Differentiating between hosts

To differentiate between different hosts, put the configuration for
each host under a `:nomad/hosts` key, then under a string key for the given
hostname, as follows:

```clojure
{:nomad/hosts {"my-laptop" {:key1 "dev-value"}
               "my-web-server" {:key1 "prod-value"}}}
```

Nomad will then put the configuration of the current host on the
`:nomad/current-host` key in the map:

```clojure

(get-in (my-config) [:nomad/current-host :key1])
;; On "my-laptop", will return "dev-value"
;; On "my-web-server", will return "prod-value"
```

### 'Instances'

Nomad also allows you to set up different 'instances' running on the
same host. To differentiate between instances, add a `:nomad/instances`
map under the given host:

```clojure
{:nomad/hosts 
	{"my-laptop" 
		{:nomad/instances
			"DEV1"
				{:data-directory "/home/me/.dev1"}
			"DEV2"
				{:data-directory "/home/me/.dev2"}}}}

```

To differentiate between instances, set the `NOMAD_INSTANCE`
environment variable before running your application:

    NOMAD_INSTANCE="DEV2" lein ring server

Then, lookup the `:nomad/current-instance` key to get the
configuration for the current instance:

```clojure
(let [{:keys [data-directory]} (:nomad/current-instance (my-config))]
	(slurp (io/file data-directory "data-file.edn")))

;; will slurp "/home/me/.dev2/data-file.edn
```

## Bugs/features/suggestions/questions?

Please feel free to submit bug reports/patches etc through the GitHub
repository in the usual way!

Thanks!

## Changes

### 0.2.0

0.2.0 has introduced a couple of breaking changes:

* `get-config`, `get-host-config` and `get-instance-config` have been
  removed. Use `defconfig` as described above in place of
  `get-config`; the current host and instance config now live under
  the `:nomad/current-host` and `:nomad/current-instance` keys
  respectively.
* Previously, Nomad expected your configuration file to be in a
  `nomad-config.edn` file at the root of the classpath. You can now
  specify the file or resource (or many, in fact, if you use several
  `defconfig` invocations) for Nomad to use.

## License

Copyright © 2013 James Henderson

Distributed under the Eclipse Public License, the same as Clojure.
