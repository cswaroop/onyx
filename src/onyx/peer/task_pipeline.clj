(ns onyx.peer.task-pipeline
  (:require [clojure.core.async :refer [alts!! <!! >!! >! chan close! go] :as async]
            [com.stuartsierra.component :as component]
            [dire.core :as dire]
            [onyx.peer.pipeline-extensions :as p-ext]
            [onyx.queue.hornetq :refer [hornetq]]
            [onyx.peer.transform :as transform]
            [onyx.extensions :as extensions]))

(defn create-tx-session [{:keys [queue]}]
  (extensions/create-tx-session queue))

(defn munge-open-session [event session]
  (assoc event :session session))

(defn munge-status-check [{:keys [sync status-node] :as event}]
  (assoc event :commit? (extensions/place-exists? sync status-node)))

(defn munge-commit-tx [{:keys [queue session] :as event}]
  (extensions/commit-tx queue session)
  (assoc event :committed true))

(defn munge-close-resources [{:keys [queue session producer consumers] :as event}]
  (extensions/close-resource queue session)
  (extensions/close-resource queue producer)
  (doseq [consumer consumers] (extensions/close-resource queue consumer))  
  (assoc event :closed true))

(defn munge-complete-task [{:keys [sync completion-node decompressed] :as event}]
  (let [done? (= (last decompressed) :done)]
    (prn decompressed " :: " done?)
    (when done?
      (extensions/touch-place sync completion-node))
    (assoc event :completed done?)))

(defn open-session-loop [read-ch pipeline-data]
  (loop []
    (when-let [session (create-tx-session pipeline-data)]
      (>!! read-ch (munge-open-session pipeline-data session))
      (recur))))

(defn read-batch-loop [read-ch decompress-ch]
  (loop []
    (when-let [event (<!! read-ch)]
      (>!! decompress-ch (p-ext/munge-read-batch event))
      (recur))))

(defn decompress-tx-loop [decompress-ch apply-fn-ch]
  (loop []
    (when-let [event (<!! decompress-ch)]
      (>!! apply-fn-ch (p-ext/munge-decompress-tx event))
      (recur))))

(defn apply-fn-loop [apply-fn-ch compress-ch]
  (loop []
    (when-let [event (<!! apply-fn-ch)]
      (>!! compress-ch (p-ext/munge-apply-fn event))
      (recur))))

(defn compress-tx-loop [compress-ch write-batch-ch]
  (loop []
    (when-let [event (<!! compress-ch)]
      (>!! write-batch-ch (p-ext/munge-compress-tx event))
      (recur))))

(defn write-batch-loop [write-ch status-check-ch]
  (loop []
    (when-let [event (<!! write-ch)]
      (>!! status-check-ch (p-ext/munge-write-batch event))
      (recur))))

(defn status-check-loop [status-ch commit-tx-ch reset-payload-node-ch]
  (loop []
    (when-let [event (<!! status-ch)]
      (let [event (munge-status-check event)]
        (if (:commit? event)
          (>!! commit-tx-ch event)
          (>!! reset-payload-node-ch event))
        (recur)))))

(defn commit-tx-loop [commit-ch close-resources-ch]
  (loop []
    (when-let [event (<!! commit-ch)]
      (>!! close-resources-ch (munge-commit-tx event))
      (recur))))

(defn close-resources-loop [close-ch complete-task-ch]
  (loop []
    (when-let [event (<!! close-ch)]
      (>!! complete-task-ch (munge-close-resources event))
      (recur))))

(defn complete-task-loop [complete-ch reset-payload-node-ch]
  (loop []
    (when-let [event (<!! complete-ch)]
      (let [event (munge-complete-task event)]
        (if (:completed event)
          (>!! reset-payload-node-ch event)))
      (recur))))

(defn reset-payload-node-loop [reset-ch]
  (loop []
    (when-let [event (<!! reset-ch)]
      (recur))))

(defrecord TaskPipeline [payload sync queue]
  component/Lifecycle

  (start [component]
    (prn "Starting Task Pipeline")
    
    (let [read-batch-ch (chan 1)
          decompress-tx-ch (chan 1)
          apply-fn-ch (chan 1)
          compress-tx-ch (chan 1)
          status-check-ch (chan 1)
          write-batch-ch (chan 1)
          commit-tx-ch (chan 1)
          close-resources-ch (chan 1)
          complete-task-ch (chan 1)
          reset-payload-node-ch (chan 1)

          pipeline-data {:ingress-queues (:task/ingress-queues (:task payload))
                         :egress-queues (:task/egress-queues (:task payload))
                         :task (:task/name (:task payload))
                         :status-node (:status (:nodes payload))
                         :completion-node (:completion (:nodes payload))
                         :catalog (read-string (extensions/read-place sync (:catalog (:nodes payload))))
                         :workflow (read-string (extensions/read-place sync (:workflow (:nodes payload))))
                         :queue queue
                         :sync sync
                         :batch-size 2
                         :timeout 50}]

      (dire/with-handler! #'open-session-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'read-batch-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'decompress-tx-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'apply-fn-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'compress-tx-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'write-batch-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'status-check-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'commit-tx-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'close-resources-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'complete-task-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'reset-payload-node-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (assoc component
        :read-batch-ch read-batch-ch
        :decompress-tx-ch decompress-tx-ch
        :apply-fn-ch apply-fn-ch
        :compress-tx-ch compress-tx-ch
        :write-batch-ch write-batch-ch
        :status-check-ch status-check-ch
        :commit-tx-ch commit-tx-ch
        :close-resources-ch close-resources-ch
        :complete-task-ch complete-task-ch
        :reset-payload-node-ch reset-payload-node-ch        
        
        :open-session-loop (future (open-session-loop read-batch-ch pipeline-data))
        :read-batch-loop (future (read-batch-loop read-batch-ch decompress-tx-ch))
        :decompress-tx-loop (future (decompress-tx-loop decompress-tx-ch apply-fn-ch))
        :apply-fn-loop (future (apply-fn-loop apply-fn-ch compress-tx-ch))
        :compress-tx-loop (future (compress-tx-loop compress-tx-ch write-batch-ch))
        :write-batch-loop (future (write-batch-loop write-batch-ch status-check-ch))
        :status-check-loop (future (status-check-loop status-check-ch commit-tx-ch reset-payload-node-ch))
        :commit-tx-loop (future (commit-tx-loop commit-tx-ch close-resources-ch))
        :close-resources-loop (future (close-resources-loop close-resources-ch complete-task-ch))
        :complete-task-loop (future (complete-task-loop complete-task-ch reset-payload-node-ch))
        :reset-payload-node-loop (future (reset-payload-node-loop reset-payload-node-ch)))))

  (stop [component]
    (prn "Stopping Task Pipeline")

    (close! (:read-batch-ch component))
    (close! (:decompress-tx-ch component))
    (close! (:apply-fn-ch component))
    (close! (:compress-tx-ch component))
    (close! (:write-batch-ch component))
    (close! (:status-check-ch component))
    (close! (:commit-tx-ch component))
    (close! (:close-resources-ch component))
    (close! (:complete-task-ch component))
    (close! (:reset-payload-node-ch component))

    (future-cancel (:open-session-loop component))
    (future-cancel (:read-batch-loop component))
    (future-cancel (:decompress-tx-loop component))
    (future-cancel (:apply-fn-loop component))
    (future-cancel (:compress-tx-loop component))
    (future-cancel (:status-check-loop component))
    (future-cancel (:write-batch-loop component))
    (future-cancel (:close-resources-loop component))
    (future-cancel (:complete-task-loop component))
    (future-cancel (:reset-payload-node-loop component))
    
    component))

(defn task-pipeline [payload sync queue]
  (map->TaskPipeline {:payload payload :sync sync :queue queue}))
