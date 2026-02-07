# Rho Framework

Rho is a modular Clojure web framework built on **Integrant** for lifecycle
management and Pedestal for HTTP. Functionality expands through module
discovery: add a module to the classpath and its capabilities are discovered
and loaded automatically.

## Repository layout

- `libs/rho-core` – config, module discovery, and system wiring.
- `libs/rho-pedestal` – Pedestal HTTP module (routes aggregation and server startup).
- `libs/rho-htmx` – HTMX helpers and response handling.
- `examples/guestbook` – minimal example module.

## Core principles

**Modules are data**
A module is a namespace with `^:rho/module` metadata and a `module` var. The
value of `module` is a map that can both:

- Contribute Integrant system fragments under `:rho/system`.
- Provide module data that other modules can consume (for example
  `:rho-pedestal/routes`).

**Discovery expands capabilities**
At startup, `rho.core.modules/find-modules` scans the classpath for namespaces
under `:rho/app-ns`. Any discovered module is loaded and its `module` map is
merged into the runtime system or exposed to other modules. Dropping a new
module on the classpath is enough to expand the app.

## Configuration

`rho.core.config` loads `config.edn` from the classpath and supports profiles
using `:rho/profile` (passed in from `rho.core.app/start!`).

Expected keys in config:

- `:rho/app-ns` – base namespace to scan for modules.
- `:rho/app-port` – port for the HTTP server.
- `:rho/base-url` – used only for logging a friendly startup message.

## Module discovery and shape

A namespace is considered a module when it has metadata `^:rho/module` and
exports a var named `module`.

```clj
(ns ^:rho/module myapp.guestbook)

(defn hello [_]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "Hello!"})

(def module
  {:rho-pedestal/routes
   #{["/hello" :get hello]}})
```

## HTTP server and routes

`rho.pedestal.routes/collect-routes` gathers `:rho-pedestal/routes` from each
module and merges them into a single Pedestal route set. `rho.pedestal` starts
the HTTP server and injects `:components` into each request so handlers can
access system state at `(:components request)`.

## Dependencies and transitive usage

- If you depend on `rho/htmx`, you already get `rho/core` and `rho/pedestal`
  transitively. This is why the guestbook example can depend only on
  `rho/htmx`.
- If you want to avoid HTMX, depend on `rho/core` or `rho/pedestal` directly.

## Running

From an app namespace with a `config.edn` on the classpath:

```clj
(rho.core.app/start! :dev)
```

Or via the main entrypoint:

```bash
clj -M -m rho.core.app dev
```

## Hot reload (dev)

From `examples/guestbook`, start a REPL with the dev alias so `dev/user.clj`
is on the classpath:

```bash
clj -M:dev
```

In the REPL:

```clj
(user/go)    ;; start the system
(user/reset) ;; reload changed namespaces and restart the system
(user/halt)  ;; stop the system
```

## Uberjar

`rho.core.jarlister` only scans `.clj` and `.cljc` entries inside JARs. When
building an uberjar, make sure your module source files are included in the JAR
alongside compiled classes. If you build a "classes-only" uberjar, module
discovery will return no namespaces.

The framework ships a build task in `rho.core.build/uber`. The example app
already includes a `:build` alias that uses it.

```clj
:aliases
{:build {:extra-deps {io.github.clojure/tools.build {:mvn/version "0.9.6"}}
         :ns-default rho.core.build}}
```

Note: You can choose between `-X` and `-T` for build aliases.
`-X` uses your project classpath (so you avoid duplicating `rho/core` and
`rho/pedestal` deps), but it is less isolated because it pulls in all project
dependencies. `-T` uses a tool-only classpath (more isolated, often faster),
but then you must include `rho/core` and `rho/pedestal` in the tool alias
dependencies so `rho.core.build` and the Pedestal module index are available.

1. Ensure your app `deps.edn` includes source and resources paths (for example
   `:paths ["src" "resources"]`).
2. Put `config.edn` in `resources/` and set `:rho/app-ns` to your app root
   namespace (for example `guestbook`).
3. Build and run:

```bash
clj -X:build uber
java -jar target/app-standalone.jar dev
```

If you use a different build tool, the key requirement is the same: the JAR on
the classpath must contain the `.clj`/`.cljc` files for your module namespaces.

## Example

See `examples/guestbook` for a minimal module and `deps.edn` setup.
