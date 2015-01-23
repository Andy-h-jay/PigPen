;;
;;
;;  Copyright 2013 Netflix, Inc.
;;
;;     Licensed under the Apache License, Version 2.0 (the "License");
;;     you may not use this file except in compliance with the License.
;;     You may obtain a copy of the License at
;;
;;         http://www.apache.org/licenses/LICENSE-2.0
;;
;;     Unless required by applicable law or agreed to in writing, software
;;     distributed under the License is distributed on an "AS IS" BASIS,
;;     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;;     See the License for the specific language governing permissions and
;;     limitations under the License.
;;
;;

(ns pigpen.rx.core
  (:require [schema.macros :as s] ;; TODO why doesn't schema.core load properly?
            [pigpen.model :as m]
            [pigpen.runtime]
            [pigpen.local :as local]
            [pigpen.oven :as oven]
            [clojure.java.io :as io]
            [clojure.core.reducers :as reducers]
            [rx.lang.clojure.core :as rx]
            [rx.lang.clojure.interop :as rx-interop]
            [rx.lang.clojure.blocking :as rx-blocking]
            [pigpen.rx.extensions :refer [multicast multicast->observable]]
            [pigpen.extensions.io :refer [list-files]]
            [pigpen.extensions.core :refer [forcat zipv]])
  (:import [rx Observable Observer Subscriber Subscription]
           [rx.schedulers Schedulers]
           [rx.observables GroupedObservable]))

(require '[pigpen.extensions.test :refer [debug]])

(defmethod pigpen.runtime/pre-process [:rx :frozen]
  [_ _]
  local/pre-process)

(defmethod pigpen.runtime/post-process [:rx :frozen]
  [_ _]
  local/post-process)

(defmethod local/eval-func :fold
  [_ {:keys [pre combinef reducef post]} [values]]
  (->> values
    (mapv local/remove-sentinel-nil)
    pre
    (reducers/fold combinef reducef)
    post
    vector))

(defmulti graph->observable (fn [data command] (:type command)))

(defn graph->observable+ [data {:keys [id ancestors] :as command}]
  ;(prn 'id id)
  (let [ancestor-data (mapv (comp multicast->observable data) ancestors)
        result (graph->observable ancestor-data command)]
    ;(prn 'result result)
    (assoc data id (multicast result))))

(defn dump
  "Executes a script locally and returns the resulting values as a clojure
sequence. This command is very useful for unit tests.

  Example:

    (->>
      (pig/load-clj \"input.clj\")
      (pig/map inc)
      (pig/filter even?)
      (pig-rx/dump)
      (clojure.core/map #(* % %))
      (clojure.core/filter even?))

    (deftest test-script
      (is (= (->>
               (pig/load-clj \"input.clj\")
               (pig/map inc)
               (pig/filter even?)
               (pig-rx/dump))
             [2 4 6])))

  Note: pig/store commands return an empty set
        pig/script commands merge their results
"
  {:added "0.1.0"}
  [query]
  (let [graph (oven/bake query :rx {} {})
        last-command (:id (last graph))]
    (->> graph
      (reduce graph->observable+ {})
      (last-command)
      (multicast->observable)
      (rx-blocking/into [])
      (map (comp val first)))))

;; ********** IO **********

(s/defmethod graph->observable :return
  [_ {:keys [data]} :- m/Return]
  (rx/seq->o data))

(s/defmethod graph->observable :load
  [_ {:keys [location], :as command} :- m/Load]
  (let [local-loader (local/load command)
        ^Observable o (->>
                        (rx/observable*
                          (fn [^Subscriber s]
                            (future
                              (try
                                (println "Start reading from " location)
                                (doseq [file (local/locations local-loader)
                                        :while (not (.isUnsubscribed s))]
                                  (let [reader (local/init-reader local-loader file)]
                                    (doseq [value (local/read local-loader reader)
                                            :while (not (.isUnsubscribed s))]
                                      (rx/on-next s value))
                                    (local/close-reader local-loader reader)))
                                (rx/on-completed s)
                                ;; TODO test this more. Errors seem to cause deadlocks
                                (catch Throwable t (rx/on-error s t))))))
                        (rx/finally
                          (println "Stop reading from " location)))]
    (-> o
      (.onBackpressureBuffer)
      (.observeOn (Schedulers/io)))))

(s/defmethod graph->observable :store
  [[data] {:keys [location], :as command} :- m/Store]
  (let [local-storage (local/store command)
        writer (delay
                 (println "Start writing to " location)
                 (local/init-writer local-storage))]
    (->> data
      (rx/do (fn [value]
               (local/write local-storage @writer value)))
      (rx/finally
        (when (realized? writer)
          (println "Stop writing to " location)
          (local/close-writer local-storage @writer))))))

;; ********** Map **********

(s/defmethod graph->observable :generate
  [[data] {:keys [projections]} :- m/Mapcat]
  (rx/flatmap
    (fn [values]
      (->> projections
        (map (partial local/graph->local values))
        (local/cross-product)
        (rx/seq->o)))
    data))

(s/defmethod graph->observable :rank
  [[data] {:keys [id]} :- m/Rank]
  (->> data
    (rx/map-indexed (fn [i v] (assoc v 'index i)))
    (rx/map (local/update-field-ids id))))

(s/defmethod graph->observable :order
  [[data] {:keys [id key comp]} :- m/Sort]
  (->> data
    (rx/sort-by key (local/pigpen-comparator comp))
    (rx/map #(dissoc % key))
    (rx/map (local/update-field-ids id))))

;; ********** Filter **********

(s/defmethod graph->observable :limit
  [[data] {:keys [id n]} :- m/Take]
  (->> data
    (rx/take n)
    (rx/map (local/update-field-ids id))))

(s/defmethod graph->observable :sample
  [[data] {:keys [id p]} :- m/Sample]
  (->> data
    (rx/filter (fn [_] (< (rand) p)))
    (rx/map (local/update-field-ids id))))

;; ********** Join **********

(s/defmethod graph->observable :reduce
  [[data] {:keys [fields arg]} :- m/Reduce]
  (->> data
    (rx/map arg)
    (rx/into [])
    (rx/mapcat (fn [vs]
                 (if (seq vs)
                   (rx/return {(first fields) vs})
                   (rx/empty))))))

(s/defmethod graph->observable :group
  [data {:keys [ancestors keys join-types fields]} :- m/Group]
  (let [[group-field & data-fields] fields
        join-types (zipmap keys join-types)]
    (->>
      ;; map
      (zipv [d data
             id ancestors
             k keys]
        (->> d
          (rx/flatmap
            (fn [values]
              (let [key (local/induce-sentinel-nil+ (values k) id)]
                (rx/seq->o
                  (for [[f v] values]
                    {;; This changes a nil values into a relation specific nil value
                     :field f
                     :key key
                     :value v})))))))
      ;; shuffle
      (apply rx/merge)
      (rx/group-by :key)
      ;; reduce
      (rx/flatmap (fn [[key key-group]]
                    (->> key-group
                      (rx/group-by :field)
                      (rx/flatmap (fn [[field field-group-o]]
                                    (->> field-group-o
                                      (rx/into [])
                                      (rx/map (fn [field-group]
                                                [field (map :value field-group)])))))
                      (rx/into
                        ;; Revert the fake nils we put in the key earlier
                        {group-field (local/remove-sentinel-nil+ key)}))))
      ; remove rows that were required, but are not present (inner joins)
      (rx/filter (complement
                   (fn [value]
                     (->> join-types
                       (some (fn [[k j]]
                               (and (= j :required)
                                    (not (contains? value k))))))))))))

(s/defmethod graph->observable :join
  [data {:keys [ancestors keys join-types fields]} :- m/Join]
  (let [seed-value (local/join-seed-value ancestors join-types)]
    (->>
      ;; map
      (zipv [d data
             id ancestors
             k keys]
        (->> d
          (rx/map (fn [values]
                    {:relation id
                     ;; This changes a nil values into a relation specific nil value
                     :key (local/induce-sentinel-nil+ (values k) id)
                     :values values}))))
      ;; shuffle
      (apply rx/merge)
      (rx/group-by :key)
      ;; reduce
      (rx/flatmap (fn [[_ key-group-o]]
                    (->> key-group-o
                      (rx/group-by :relation)
                      (rx/flatmap (fn [[relation relation-grouping-o]]
                                    (->> relation-grouping-o
                                      (rx/into [])
                                      (rx/map (fn [relation-grouping]
                                                [relation (map :values relation-grouping)])))))
                      (rx/into seed-value)
                      (rx/map vals)
                      (rx/flatmap (comp rx/seq->o local/cross-product))))))))

;; ********** Set **********

(s/defmethod graph->observable :distinct
  [[data] {:keys [id]} :- m/Distinct]
  (->> data
    (rx/distinct)
    (rx/map (local/update-field-ids id))))

(s/defmethod graph->observable :union
  [data {:keys [id]} :- m/Concat]
  (->> data
    (apply rx/merge)
    (rx/map (local/update-field-ids id))))

;; ********** Script **********

(s/defmethod graph->observable :noop
  [[data] {:keys [id]} :- m/NoOp]
  (rx/map (local/update-field-ids id) data))

(s/defmethod graph->observable :script
  [data _]
  (apply rx/merge (vec data)))
