(ns ^:rho/module basic.plain)

(defn hello [_]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "Hello from trivial file"})

(def module
  {:rho-pedestal/routes #{["/plain/hello" :get hello]}})
