(ns melt.integration-test
  (:require [clojure.java.jdbc :as jdbc]
            [melt.analyze :as a]
            [melt.config :refer [db]]
            [melt.diff :as d]
            [melt.load-kafka :as lk]
            [melt.read-topic :as rt]
            [melt.sync :as s]
            [midje.sweet :refer [facts fact => contains]]))

;; Tests are dependent on each other. order matters and predecessor tests failing
;; will likely cause successors to fail.

(def bootstrap-servers
  (str (or (System/getenv "TEST_KAFKA_HOST") "localhost") ":9092"))

(def producer-props
  (doto (java.util.Properties.)
    (.put "bootstrap.servers" bootstrap-servers)
    (.put "acks" "all")
    (.put "key.serializer" "org.apache.kafka.common.serialization.StringSerializer")
    (.put "value.serializer" "org.apache.kafka.common.serialization.StringSerializer")))

(def consumer-props
  (doto (java.util.Properties.)
    (.put "bootstrap.servers" bootstrap-servers)
    (.put "key.deserializer" "org.apache.kafka.common.serialization.StringDeserializer")
    (.put "value.deserializer" "org.apache.kafka.common.serialization.StringDeserializer")
    (.put "group.id" "melt.integration-test")))

(def schema (a/schema))

(if (= "TRUE" (System/getenv "RESET_INTEGRATION"))
  (jdbc/update! db "saleslt.address" {:postalcode "98626"} ["addressid = ?" 888]))

(fact "a table written to a topic may be read from a topic"
      (lk/load-topics {:producer-properties producer-props})
      ((rt/read-topic consumer-props "melt.SalesLT.Address") {:addressid 603})
      =>
      {"city"          "Killeen"
       "addressline2"  nil
       "modifieddate"  "2007-08-01"
       "rowguid"       "0E6E9E86-A637-4FD5-A945-AC342BFD715B"
       "postalcode"    "76541"
       "addressline1"  "9500b E. Central Texas Expressway"
       "countryregion" "United States"
       "stateprovince" "Texas"
       "addressid"     603})

(let [table (first (filter #(= (:name %) "Address") schema))]
  (facts "`about diff`"

         (fact "finds no differences between a source table and target topic after initial load"
               (d/diff consumer-props table)
               =>
               {:table-only {}
                :topic-only {}})

         (fact "finds differences when the table has changed"
               (jdbc/update! db "saleslt.address" {:postalcode "99995"} ["addressid = ?" 888]) => [1]

               (let [diff (d/diff consumer-props table)]
                 (get-in diff [:table-only {:addressid 888}]) => (contains {:postalcode "99995"})
                 (get-in diff [:topic-only {:addressid 888}]) => (contains {"postalcode" "98626"}))))

  (fact "`sync` publishes differences in a table to the topic to bring them back in sync"
        (s/sync consumer-props producer-props table)
        (d/diff consumer-props table)
        =>
        {:table-only {}
         :topic-only {}}))
