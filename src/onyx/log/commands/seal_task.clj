(ns onyx.log.commands.seal-task
  (:require [clojure.core.async :refer [>!!]]
            [com.stuartsierra.component :as component]
            [onyx.log.commands.common :as common]
            [onyx.log.entry :refer [create-log-entry]]
            [onyx.extensions :as extensions]))

(defn should-seal? [replica args]
  (let [status (common/task-status replica (:job args) (:task args))
        one-active-peer? (= (get status :active 0) 1)
        this-peer-is-active? (= (get-in replica [:peer-state (:id args)]) :active)]
    (boolean (and one-active-peer? this-peer-is-active?))))

(defmethod extensions/apply-log-entry :seal-task
  [{:keys [args]} replica]
  (if-not (should-seal? replica args)
    (let [peer (get-in replica [:sealing-task (:task args)])]
      (-> replica
          (assoc-in [:sealing-task (:task args)] (or peer true))
          (assoc-in [:peer-state (:id args)] :idle)
          (common/remove-peers args)))
    (assoc-in replica [:sealing-task (:task args)] (:id args))))

(defmethod extensions/replica-diff :seal-task
  [{:keys [args]} old new]
  {:seal? (= (:peer-state old) (:peer-state new))})

(defmethod extensions/reactions :seal-task
  [{:keys [args]} old new diff peer-args]
  [])

(defmethod extensions/fire-side-effects! :seal-task
  [{:keys [args]} old new diff state]
  (if (= (:id args) (:id state))
    (if (:seal? diff)
      (do (>!! (:seal-response-ch state) true)
          state)
      (do (>!! (:seal-response-ch state) false)
          (component/stop (:lifecycle state))
          (let [entry (create-log-entry :volunteer-for-task {:id (:id state)})]
            (>!! (:outbox-ch state) entry))
          (assoc state :lifecycle nil :job nil :task nil)))
    state))

