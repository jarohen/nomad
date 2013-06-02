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
;; stable
[jarohen/nomad "0.3.1"]

;; bug-fixes only
[jarohen/nomad "0.2.1"]
```

Version 0.3.x introduces a large breaking change to 0.2.x, namely that
current host/instance config is now all merged into one consolidated
map. Please do not just update your project.clj version without
testing!

Version 0.2.x will now be maintained with bug-fixes, but no new
features will be backported.

Please see 'Changes', below.

### 'Hello world!'

Nomad expects your configuration to be stored in an [EDN][1]
file. Nomad does expect a particular structure for your configuration,
however it will load any data structure in the file.

[1]: https://github.com/edn-format/edn

To load the data structure in the file, use the `defconfig` macro,
passing in either a file or a classpath resource:

my-config.edn:

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

Nomad will then merge the configuration of the current host into the
returned map:

```clojure

(get-in (my-config) [:key1])
;; On "my-laptop", will return "dev-value"
;; On "my-web-server", will return "prod-value"

;; Previously (0.2.x), you would have to have done:
;; (get-in (my-config) [:nomad/current-host :key1])
```

Nomad also adds the `:nomad/hostname` key to the map, with the
hostname of the current machine.

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

Then, the current instance configuration will also be merged into the
map:

```clojure
(let [{:keys [data-directory]} (my-config)]
	(slurp (io/file data-directory "data-file.edn")))

;; will slurp "/home/me/.dev2/data-file.edn
```

Similarly to the current host, Nomad adds a `:nomad/instance` key to
the map, with the name of the current instance.

### Referring to file paths

You can use the ```#nomad/file``` reader macro to declare files in
your configuration, in addition to the usual Clojure reader macros. 

my-config.edn:

```clojure
{:nomad/hosts
	{"my-host"
		{:data-directory #nomad/file "/home/james/.my-app"}}}
```

my_ns.clj:

```clojure
(ns my-ns
    (:require [nomad :refer [defconfig]
              [clojure.java.io :as io]]))

(defconfig my-config (io/resource "config/my-config.edn"))

(type (:data-directory (my-config)))
;; -> java.io.File
```

(This reader macro only applies for the configuration file, and will
not impact the rest of your application. Having said this, Nomad is
open-source - so please feel free to pinch the two lines of code that
it took to implement this!)

### 'Snippets'

Snippets (introduced in v0.3.1) allow you to refer to shared snippets
of configuration from within your individual host/instance maps.

#### Why snippets?

I've found, both through my usage of Nomad and through feedback from
others, that a lot of host-specific config is duplicated between
similar hosts. 

One example that comes up time and time again is database
configuration - while it does differ from host to host, most hosts
select from one of only a small number of distinct configurations
(i.e. dev databases vs staging vs prod). Previously, this would mean
either duplicating each database's configuration in each of the hosts
that used it, or implementing a level of indirection in each project
that uses Nomad.

The introduction of 'snippets' means that each distinct database
configuration only needs to be declared once, and each host simply
contains a pointer to the relevant snippet.

#### Using snippets

Snippets are declared under the `:nomad/snippets` key at the top level
of your configuration map:

```clojure
{:nomad/snippets
	{:databases
	    {:dev {:host "dev-host"
		       :user "dev-user"}}
		 :prod {:host "prod-host"
		        :user "prod-user"}}}
```

You can then refer to them using the `#nomad/snippet` reader macro,
passing a vector of keys to navigate down into the snippets map. So,
for example, to refer to the `:dev` database, use `#nomad/snippet
[:databases :dev]` in your host config, as follows:

```clojure
{:nomad/snippets { ... as before ... }
 :nomad/hosts
	{"my-host"
	     {:database #nomad/snippet [:databases :dev]}
	 "prod-host"
		 {:database #nomad/snippet [:databases :prod]}}}
```

When you query the configuration map for the database host, Nomad will
return your configuration map, but with the snippet dereferenced:

```clojure
(ns my-ns
    (:require [nomad :refer [defconfig]
              [clojure.java.io :as io]]))

(defconfig my-config (io/resource "config/my-config.edn"))

(my-config)
;; on "my-host"
;; -> {:database {:host "dev-host"
;;                :user "dev-user"}
;;     ... }
```


## Private configuration

Some configuration probably shouldn't belong in source code control -
i.e. passwords, credentials, production secrets etc. Nomad allows you
to define 'private configuration files' - a reference to either host-
or instance-specific files outside of your classpath to include in the
configuration map.

To do this, include a `:nomad/private-file` key in either your host or
instance config, pointing to a file on the local file system:

my-config.edn:

```clojure
{:nomad/hosts
	{"my-host"
		;; Using the '#nomad/file' reader macro
		{:nomad/private-file #nomad/file "/home/me/.my-app/secret-config.edn"
		{:database {:username "my-user"
		            :password :will-be-overridden}}}}}
```

/home/me/.my-app/secret-config.edn
(outside of source code)

```clojure
{:database {:password "password123"}}
;; because all the best passwords are... ;)
```

The private configuration is recursively merged into the public host
configuration, as follows:

my_ns.clj:

```clojure
(ns my-ns
    (:require [nomad :refer [defconfig]
              [clojure.java.io :as io]]))

(defconfig my-config (io/resource "config/my-config.edn"))

(get-in (my-config) [:database])
;; -> {:username "my-user", :password "password123"}
```

## Order of preference

Nomad now merges all of your public/private/host/instance
configuration into one big map, with the following priorities (in
decreasing order of preference):

* Private instance config
* Public instance config
* Private host config
* Public host config
* Other config outside of `:nomad/hosts`

### Where does that config value come from?!?!

Nomad stores the individual components of the configuration as
meta-information on the returned config:

```clojure
(ns my-ns
    (:require [nomad :refer [defconfig]
              [clojure.java.io :as io]]))

(defconfig my-config (io/resource "config/my-config.edn"))

(meta (my-config))
;; -> {:general {:config ...}
;;     :host {:config ...}
;;     :host-private {:config ...}
;;     :instance {:config ...}
;;     :instance-private {:config ...}}
```


## Configuration structure - Summary (legacy)

**(this only applies to the legacy 0.2.x branch, included for
  posterity. In 0.3.x and later, this is all merged into one map)**

The structure of the resulting configuration map is as follows:

* `:nomad/hosts` - the configuration for all of the hosts
    * `"hostname"`
        * `:nomad/instances` - the configuration for all of the
          instances on this host
            * `"instance-name" { ... }`
            * `"another-instance" { ... }`
        * `...` - other host-related configuration
    * `"other-host" { ... }`
* `:nomad/current-host { ... }` - added by Nomad at run-time: the
  configuration of the current host (copied from the host map, above),
  merged with any code from the current host's private configuration
  file.
* `:nomad/current-instance { ... }` - added by Nomad at run-time: the
  configuration of the current instance (copied from the instance
  map), merged with any code from the current instance's private
  configuration file.


## Bugs/features/suggestions/questions?

Please feel free to submit bug reports/patches etc through the GitHub
repository in the usual way!

Thanks!

## Changes

### 0.3.1

Introduced 'snippets' using the `:nomad/snippets` key and the
`#nomad/snippet` reader macro.

No breaking changes.

### 0.3.0

0.3.0 introduces a rather large breaking change: in the outputted
configuration map, rather than lots of :nomad/* keys, all of the
current host/current instance maps are merged into the main output map.

In general, you should just be able to replace:

* `(get-in (my-config) [:nomad/current-host :x :y])` with `(get-in
  (my-config) [:x :y])`
  
and

* `(get-in (my-config) [:nomad/current-instance :x :y])` with `(get-in
  (my-config) [:x :y])`
  
unless you have conflicting key names in your general configuration.


### 0.2.1

Mainly the addition of the private configuration - no breaking changes.

* Allowed users to add `:nomad/private-file` key to host/instance maps
  to specify a private configuration file, which is merged into the
  `:nomad/current-host` and `:nomad/current-instance` maps.
* Added `#nomad/file` reader macro
* Added `:nomad/hostname` and `:nomad/instance` keys to
  `:nomad/current-host` and `:nomad/current-instance` maps
  respectively.

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
  
### 0.1.0

Initial release

## License

Copyright © 2013 James Henderson

Distributed under the Eclipse Public License, the same as Clojure.
