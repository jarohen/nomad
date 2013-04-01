# nomad

A Clojure library designed to allow Clojure configuration to travel between hosts.

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

Add the ``nomad`` dependency to your ```project.clj```

```clojure
[jarohen/nomad "0.1.0"]
```



## License

Copyright © 2013 James Henderson

Distributed under the Eclipse Public License, the same as Clojure.
