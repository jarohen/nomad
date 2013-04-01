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

Add the ``nomad`` dependency to your ```project.clj```

```clojure
[jarohen/nomad "0.1.0"]
```

Nomad expects your configuration to be stored in an [1](EDN) file
called ``nomad-config.edn`` in the root of your classpath. Nomad does
expect a particular structure for your configuration, however it will
load any data structure in the file.

1: https://github.com/clojure/edn

To load the data structure in the file, use the ```get-config``` function:

nomad-config.edn:

```clojure
{:my-key "my-value"}
```

your-ns.clj:

```clojure
(require '[nomad :refer [get-config]])

(get-config)
;; -> {:my-key "my-value"}
```

### Caching

Nomad will cache the configuration where possible, but will
auto-reload the configuration if the underlying file is modified.

### Differentiating between hosts

To differentiate between different hosts, put the configuration for
each host under a ```:hosts``` key, then under a string key for the given
hostname, as follows:

```clojure
{:hosts {"my-laptop" {:key1 "dev-value"}
         "my-web-server" {:key1 "prod-value"}}}
```

To access the configuration for the current host, call
```get-host-config```:

```clojure
(require '[nomad :refer [get-host-config]])

(:key1 (get-host-config))
;; On "my-laptop", will return "dev-value"
;; On "my-web-server", will return "prod-value"
```

### 'Instances'

Nomad also allows you to set up different 'instances' running on the
same host. To differentiate between instances, add an ```:instances```
map under the given host:

```clojure
{:hosts 
	{"my-laptop" 
		{:instances
			"DEV1"
				{:data-directory "/home/me/.dev1"}
			"DEV2"
				{:data-directory "/home/me/.dev2"}}}}

```

To differentiate between insptances, set the ```NOMAD_INSTANCE```
environment variable before running your application:

    NOMAD_INSTANCE="DEV2" lein ring server

Then, call ```(get-instance-config)``` to get the configuration for
the current instance:


```clojure
(let [{:keys [data-directory]} (get-instance-config)]
	(slurp (io/file data-directory "data-file.edn")))

;; will slurp "/home/me/.dev2/data-file.edn
```

## Bugs/features/suggestions/questions?

Please feel free to submit bug reports/patches etc through the GitHub
repository in the usual way!

Thanks!

## License

Copyright © 2013 James Henderson

Distributed under the Eclipse Public License, the same as Clojure.
