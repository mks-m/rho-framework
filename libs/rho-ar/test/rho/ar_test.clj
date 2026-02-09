(ns rho.ar-test
  (:require [clojure.test :refer [deftest is testing]]
            [rho.ar :as ar]))

(ar/defmodel Todo {})

(defrecord FakeAdapter [queries ones execs results]
  ar/Adapter
  (query [_ sql-map opts]
    (swap! queries conj {:sql sql-map :opts opts})
    (:query @results))
  (query-one [_ sql-map opts]
    (swap! ones conj {:sql sql-map :opts opts})
    (:query-one @results))
  (execute! [_ sql-map opts]
    (swap! execs conj {:sql sql-map :opts opts})
    (:execute @results)))

(defn- fake-adapter
  [results]
  (let [queries (atom [])
        ones (atom [])
        execs (atom [])
        results (atom results)]
    {:adapter (->FakeAdapter queries ones execs results)
     :queries queries
     :ones ones
     :execs execs
     :results results}))

(deftest defmodel-basics
  (let [todo (->Todo 1 "Write tests")]
    (is (= todo-model (ar/model-of todo)))
    (is (= :todos (ar/table todo-model)))
    (is (= :id (ar/primary-key todo-model)))
    (is (= [:id :title] (ar/columns todo-model)))))

(deftest where-builds-sql
  (let [{:keys [adapter queries]} (fake-adapter {:query [{:id 1 :title "A"}]})
        rows (ar/where adapter todo-model (array-map :id 1 :title "A"))
        sql (:sql (first @queries))]
    (is (= {:select [:id :title]
            :from [:todos]
            :where [:and [:= :id 1] [:= :title "A"]]}
           sql))
    (is (= "A" (:title (first rows))))
    (is (instance? Todo (first rows)))))

(deftest find-builds-sql
  (let [{:keys [adapter ones]} (fake-adapter {:query-one {:id 9 :title "X"}})
        todo (ar/find adapter todo-model 9)
        sql (:sql (first @ones))]
    (is (= {:select [:id :title]
            :from [:todos]
            :where [:= :id 9]}
           sql))
    (is (= 9 (:id todo)))))

(deftest create-builds-sql
  (let [{:keys [adapter execs]} (fake-adapter {:execute {:id 10}})
        todo (ar/create! adapter todo-model {:title "New" :ignored true})
        sql (:sql (first @execs))]
    (is (= {:insert-into :todos
            :values [{:title "New"}]}
           sql))
    (is (= 10 (:id todo)))))

(deftest update-builds-sql
  (let [{:keys [adapter execs]} (fake-adapter {:execute {:next.jdbc/update-count 1}
                                               :query-one {:id 5 :title "Done"}})
        todo (ar/update! adapter todo-model {:id 5 :title "Done"})
        sql (:sql (first @execs))]
    (is (= {:update :todos
            :set {:title "Done"}
            :where [:= :id 5]}
           sql))
    (is (= 5 (:id todo)))))

(deftest delete-builds-sql
  (let [{:keys [adapter execs]} (fake-adapter {:execute {:next.jdbc/update-count 1}})
        count (ar/delete! adapter todo-model 5)
        sql (:sql (first @execs))]
    (is (= {:delete-from :todos
            :where [:= :id 5]}
           sql))
    (is (= 1 count))))

(deftest save-branches
  (testing "create when missing primary key"
    (let [{:keys [adapter execs]} (fake-adapter {:execute {:id 11}})
          todo (ar/save! adapter (->Todo nil "Draft"))
          sql (:sql (first @execs))]
      (is (= {:insert-into :todos
              :values [{:title "Draft"}]}
             sql))
      (is (= 11 (:id todo)))))
  (testing "update when primary key is present"
    (let [{:keys [adapter execs]} (fake-adapter {:execute {:next.jdbc/update-count 1}
                                                 :query-one {:id 2 :title "Saved"}})
          todo (ar/save! adapter (->Todo 2 "Saved"))
          sql (:sql (first @execs))]
      (is (= {:update :todos
              :set {:title "Saved"}
              :where [:= :id 2]}
             sql))
      (is (= 2 (:id todo))))))
