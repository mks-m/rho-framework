(ns ^:rho/module basic
  (:require [io.pedestal.http.route :as route]
            [rho.pedestal.html :as html]))

(defn index [_]
  (html/response
   {:title "Basic example - Index"
    :body [[:main
            [:h1 "Welcome to this page!"]
            [:p "This is the main page."]
            [:p [:a {:href (route/url-for :basic.plain/hello)}
                 "Plain text endpoint"]]
            [:p [:a {:href (route/url-for :basic.hiccup/default-page)}
                 "Default template example"]]
            [:p [:a {:href (route/url-for :basic.hiccup/custom-page)}
                 "Custom template example"]]
            [:p [:a {:href (route/url-for :basic.htmx/htmx-page)}
                 "HTMX example"]]]]}))

(def module
  {:rho-pedestal/routes #{["/" :get #'index]}})
