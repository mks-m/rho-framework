# rho-ar

ActiveRecord-style helpers for Rho using Clojure records and protocols.

## Example usage

```clj
(ns my.app.todos
  (:require [rho.ar :as ar]
            [rho.pedestal.html :as html]))

;; table name is inferred from the name, columns are looked up in schema.edn
(ar/defmodel Todo {})

(defn index [{{:keys [db]} :components}]
  (let [todos (all db {:order-by [[:created-at :desc]]})]
    (html/response
     {:title "Todos"
      :body [[:main
              [:h1 "Todos"]
              [:ul (for [{:keys [id title]} todos]
                     [:li {:data-id id} title])]]]})))

(defn add [{{:keys [db]} :components :as request}]
  (let [title (some-> request :form-params :title str not-empty)]
    (when title
      (create! db {:title title}))
    {:status 303
     :headers {"Location" "/"}}))
```

### Notes

- `next-jdbc-adapter` works with the `rho-sqlite` datasource component (`:db`).
- `defmodel` defines a record type and a `<name>-model` var (e.g. `todo-model`).
- `defmodel` also emits local helpers like `all`, `find`, `create!`, etc. The prefix is automatic when the namespace does not match the table name, or you can override with `:prefix`.
- The generated helpers accept either a datasource/connectable or a pre-built adapter; a `next-jdbc-adapter` is created automatically when needed.
- Use `ar/find`, `ar/where`, `ar/update!`, `ar/delete!`, and `ar/save!` for basic CRUD.
