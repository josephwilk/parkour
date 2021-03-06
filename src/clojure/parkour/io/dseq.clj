(ns parkour.io.dseq
  (:require [clojure.core.protocols :as ccp]
            [clojure.core.reducers :as r]
            [parkour (conf :as conf) (cstep :as cstep) (wrapper :as w)]
            [parkour.mapreduce (source :as src)]
            [parkour.io.dseq (mapred :as mr1) (mapreduce :as mr2)]
            [parkour.util :refer [ignore-errors]])
  (:import [java.io Closeable Writer]
           [clojure.lang IObj]))

(defprotocol DSeqable
  "Protocol for producing distribute sequence."
  (-dseq [this] "Return distributed sequence for `this`."))

(defn input-format
  "Input format class for `job`, or `nil` if unspecified."
  {:tag `Class}
  [job]
  (or (conf/get-class job "mapreduce.inputformat.class" nil)
      (conf/get-class job "mapred.input.format.class" nil)))

(defn source-for
  "Local source for reading tuples from `dseq`.  Must `.close` to release
resources, as via `with-open`.  If the `raw?` keyword argument is true, then the
tuple source will not automatically unwrap values."
  {:tag `Closeable}
  [dseq & {:keys [raw?]}]
  (let [job (cstep/apply! dseq), klass (input-format job)
        tuple-source (cond (mr1/input-format? klass) mr1/tuple-source
                           (mr2/input-format? klass) mr2/tuple-source)
        source (tuple-source job klass)]
    (if raw?
      source
      (src/unwrap-source source))))

(deftype DSeq [meta step]
  Object
  (toString [this]
    (str "conf=" (-> step cstep/step-map pr-str ignore-errors (or "?"))))

  IObj
  (meta [_] meta)
  (withMeta [_ meta] (DSeq. meta step))

  cstep/ConfigStep
  (-apply! [_ job] (cstep/apply! job step))

  ccp/CollReduce
  (coll-reduce [this f] (ccp/coll-reduce this f (f)))
  (coll-reduce [this f init]
    (with-open [source (source-for step)]
      (ccp/coll-reduce source f init)))

  DSeqable
  (-dseq [this] this))

(extend-protocol DSeqable
  nil (-dseq [_] nil)
  Object (-dseq [step] (DSeq. (meta step) step)))

(defn dseq?
  "True iff `x` is a distributed sequence."
  [x] (instance? DSeq x))

(defn dseq
  "Return the distributed sequence represented by dseq-able object or
job configuration step `obj`.  Result is a config step and reducible."
  [obj] (-dseq obj))
