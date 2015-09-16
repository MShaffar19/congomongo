(ns somnium.test.congomongo
  (:use clojure.test
        somnium.congomongo
        somnium.congomongo.config
        somnium.congomongo.coerce
        clojure.pprint)
  (:require [clojure.data.json :refer (read-str write-str)]
            [clojure.set :as set])
  (:import [com.mongodb DB DBCollection DBObject BasicDBObject BasicDBObjectBuilder
                        MongoClient MongoException DuplicateKeyException MongoCommandException
                        Tag TagSet
            ReadPreference
            WriteConcern]
           [org.bson.types ObjectId]))

(deftest coercions
  (let [clojure      (array-map :a
                                (array-map :b "c" :d 1 :f ["a" "b" "c"] :g
                                           (array-map :h ["i" "j" -42.42])))
        mongo        (.. (BasicDBObjectBuilder/start)
                         (push "a")
                         (add "b" "c")
                         (add "d" 1)
                         (add "f" ["a" "b" "c"])
                         (push "g")
                         (add "h" ["i" "j" -42.42])
                         get)
        clojure-json (write-str clojure); no padding
        mongo-json   (str mongo)        ; contains whitespace padding
        from         {:clojure clojure
                      :mongo   mongo
                      :json    clojure-json}
        to           (assoc from nil nil)]
    (doseq [[from original] from, [to expected] to
            :let [actual    (coerce original [from to])]]
      (cond (= [from to] [:json :mongo]); BasicDBObject not =
                                        ; changed in 2.12.0 driver
            (is (= (.toString actual) (.toString expected)) [from to])
            (= [from to] [:mongo :json]); padding difference
            (is (= actual mongo-json) [from to])
            :else
            (is (= actual expected) [from to])))))

(def test-db-host (get (System/getenv) "MONGOHOST" "127.0.0.1"))
(def test-db-port (Integer/parseInt (get (System/getenv) "MONGOPORT" "27017")))
(def test-db-user (get (System/getenv) "MONGOUSER" nil))
(def test-db-pass (get (System/getenv) "MONGOPASS" nil))
(def test-db "congomongotestdb")
(defn- drop-test-collections!
  "When we can't drop the test database (because it requires admin rights),
   we just drop all the non-system connections."
  []
  (doseq [^String coll (collections)]
    (when-not (.startsWith coll "system")
      (drop-coll! coll))))

(defn setup! []
  (mongo! :db test-db :host test-db-host :port test-db-port)
  (when (and test-db-user test-db-pass)
    (authenticate test-db-user test-db-pass)
    (drop-test-collections!)))

(defn teardown! []
  (if (and test-db-user test-db-pass)
    (try ; some tests don't authenticate so ignore failures here:
      (drop-test-collections!)
      (catch Exception _))
    (drop-database! test-db)))

(defmacro with-test-mongo [& body]
  `(do
     (setup!)
     (try
      ~@body
      (finally
        (teardown!)))))

(deftest options-on-connections
  (with-test-mongo
    ;; set some non-default option values
    (let [a (make-connection "congomongotest-db-a" :host test-db-host :port test-db-port
                             (mongo-options :auto-connect-retry true
                                            :write-concern (:acknowledged write-concern-map)))
         ^MongoClient m (:mongo a)
          opts (.getMongoOptions m)]
      ;; check non-default options attached to Mongo object
      (is (.isAutoConnectRetry opts))
      (is (= WriteConcern/ACKNOWLEDGED (.getWriteConcern opts)))
      ;; check a default option as well
      (is (not (.slaveOk opts))))))

(deftest uri-for-connection
  (with-test-mongo
    (let [userpass (if (and test-db-user test-db-pass) (str test-db-user ":" test-db-pass "@") "")
          uri (str "mongodb://" userpass test-db-host ":" test-db-port "/congomongotest-db-a?maxpoolsize=123&w=1&safe=true")
          a (make-connection uri)
          ^MongoClient m (:mongo a)
          opts (.getMongoOptions m)]
      (testing "make-connection parses options from URI"
        (is (= 123 (.getConnectionsPerHost opts)))
        (is (= WriteConcern/ACKNOWLEDGED (.getWriteConcern opts))))
      (with-mongo a
        (testing "make-connection accepts Mongo URI"
                (is (= "congomongotest-db-a" (.getName ^DB (*mongo-config* :db)))))))))

(deftest with-mongo-database
  (with-test-mongo
    (let [a (make-connection "congomongotest-db-a" :host test-db-host :port test-db-port)]
      (with-mongo a
        (with-db "congomongotest-db-b"
          (testing "with-mongo uses new database"
                   (is (= "congomongotest-db-b" (.getName ^DB (*mongo-config* :db))))))
        (testing "with-mongo uses connection db "
                 (is (= "congomongotest-db-a" (.getName ^DB (*mongo-config* :db)))))))))

(deftest with-mongo-interactions
  (with-test-mongo
    (let [a (make-connection "congomongotest-db-a" :host test-db-host :port test-db-port)
          b (make-connection :congomongotest-db-b :host test-db-host :port test-db-port)]
      (with-mongo a
        (testing "with-mongo sets the mongo-config"
          (is (= "congomongotest-db-a" (.getName ^DB (*mongo-config* :db)))))
        (testing "mongo! inside with-mongo stomps on current config"
          (mongo! :db "congomongotest-db-b" :host test-db-host :port test-db-port)
          (is (= "congomongotest-db-b" (.getName ^DB (*mongo-config* :db))))))
      (testing "and previous mongo! inside with-mongo is visible afterwards"
        (is (= "congomongotest-db-b" (.getName ^DB (*mongo-config* :db))))))))

(deftest closing-with-mongo
  (with-test-mongo
    (let [a (make-connection "congomongotest-db-a" :host test-db-host :port test-db-port)]
      (with-mongo a
        (testing "close-connection inside with-mongo sets mongo-config to nil"
          (close-connection a)
          (is (= nil *mongo-config*)))))))

(deftest query-options
  (are [x y] (= (calculate-query-options x) y)
       nil 0
       [] 0
       [:tailable] 2
       [:tailable :slaveok] 6
       [:tailable :slaveok :notimeout] 22
       :notimeout 16))

(deftest fetch-with-options
  (with-test-mongo
    (insert! :thingies {:foo 1})
    (is (= 1 (-> (fetch :thingies :where {:foo 1} :options nil) first :foo)))
    (is (= 1 (-> (fetch :thingies :where {:foo 1} :options []) first :foo)))
    (is (= 1 (-> (fetch :thingies :where {:foo 1} :options :notimeout) first :foo)))
    (is (= 1 (-> (fetch :thingies :where {:foo 1} :options [:notimeout]) first :foo)))))

(deftest fetch-with-read-preferences
  (with-test-mongo
    (insert! :thingies {:foo 1})
    (is (= 1 (-> (fetch :thingies :where {:foo 1} :read-preference nil) first :foo)))
    (is (= 1 (-> (fetch :thingies :where {:foo 1} :read-preference :primary) first :foo)))
    (is (= 1 (-> (fetch :thingies :where {:foo 1} :read-preference :nearest) first :foo)))))


(deftest test-fetch-and-modify
  (with-test-mongo
    (insert! :test_col {:key "123"
                        :value 1})
    (fetch-and-modify :test_col {:key "123"} {:$inc {:value 2}})
    (is (= 3 (:value (fetch-one :test_col :where {:key "123"}))))
    (let [res (fetch-and-modify :test_col {:key "123"} {:$inc {:value 1}} :only [:value] :return-new? true)]
      (is (not (contains? res :key)))
      (is (= 4 (:value res))))))

(deftest can-insert-sets
  (with-test-mongo
    (insert! :test_col {:num-set #{1 2 3}
                        :kw-set #{:key1 :key2}})
    (is (= #{1 2 3} (set (:num-set (fetch-one :test_col)))))
    (is (= #{"key1" "key2"} (set (:kw-set (fetch-one :test_col)))))))

;; TODO Uncomment this test after upgrading java driver, see issue #148
; (deftest can-insert-bigdecimal
;   (let [numbers [(BigDecimal. 123)
;                  (BigDecimal. "12345678901234567890")
;                  (BigDecimal. (long 42))
;                  (BigDecimal. "42.0")
;                  (BigDecimal. (double 42))
;                  (BigDecimal. "1.2345678901234567890")
;                  (BigDecimal. Long/MAX_VALUE)
;                  (BigDecimal. Long/MIN_VALUE)
;                  (BigDecimal. 0)]]
;     (with-test-mongo
;       (insert! :test_col {:_id "can-insert-bigdecimal" :numbers numbers})
;       (is (= numbers (:numbers (fetch-one :test_col :where {:_id "can-insert-bigdecimal"})))))))

(deftest collection-existence
  (with-test-mongo
    (insert! :notbogus {:foo "bar"})
    (is (collection-exists? :notbogus))
    (is (not (collection-exists? :bogus)))
    (create-collection! :no-options-so-deferred-creation)
    (is (not (collection-exists? :no-options-so-deferred-creation)))))

(deftest capped-collections
  (with-test-mongo
    (create-collection! :cappedcoll :capped true :max 2 :size 1000)
    (is (collection-exists? :cappedcoll))
    (insert! :cappedcoll {:foo 1 :bar 1})
    (insert! :cappedcoll {:foo 1 :bar 2})
    (insert! :cappedcoll {:foo 1 :bar 3})
    (let [results (fetch :cappedcoll :where {:foo 1})]
      (is (= [2 3] (map :bar (take 2 results)))))))

(deftest fetch-sort
  (with-test-mongo
    (let [unsorted [3 10 7 0 2]]
      (mass-insert! :points
                  (for [i unsorted]
                    {:x i}))
      (is (= (map :x (fetch :points :sort {:x 1})) (sort unsorted)))
      (is (= (map :x (fetch :points :sort {:x -1})) (reverse (sort unsorted)))))))

(deftest fetch-sort-multiple
  (with-test-mongo
    (let [unsorted [3 10 7 0 2]]
      (mass-insert! :points
                  (for [i unsorted j unsorted]
                    {:x i :y j}))
      (is (= (map :x (fetch :points :sort (coerce-ordered-fields [:x :y]))) (mapcat (partial repeat (count unsorted)) (sort unsorted))))
      (is (= (map :y (fetch :points :sort (coerce-ordered-fields [:x :y]))) (apply concat (repeat (count unsorted) (sort unsorted)))))
      (is (= (map :x (fetch :points :sort (coerce-ordered-fields [:x [:y -1]]))) (mapcat (partial repeat (count unsorted)) (sort unsorted))))
      (is (= (map :y (fetch :points :sort (coerce-ordered-fields [:x [:y -1]]))) (apply concat (repeat (count unsorted) (reverse (sort unsorted))))))
      (is (= (map :x (fetch :points :sort (dbobject :x 1 :y 1))) (mapcat (partial repeat (count unsorted)) (sort unsorted))))
      (is (= (map :y (fetch :points :sort (dbobject :x 1 :y 1))) (apply concat (repeat (count unsorted) (sort unsorted)))))
      (is (= (map :x (fetch :points :sort (dbobject :x 1 :y -1))) (mapcat (partial repeat (count unsorted)) (sort unsorted))))
      (is (= (map :y (fetch :points :sort (dbobject :x 1 :y -1))) (apply concat (repeat (count unsorted) (reverse (sort unsorted)))))))))

(deftest fetch-one-sort-not-allowed
  (with-test-mongo
    (is (thrown? IllegalArgumentException
                 (fetch :stuff :sort {:a 1} :one? true)))
    (is (thrown? IllegalArgumentException
                 (fetch-one :stuff :sort {:a 1})))))

(deftest fetch-one-with-read-preferences-fails
  (with-test-mongo
    (is (thrown? IllegalArgumentException
                 (fetch-one :test_col :read-preferences :secondary)))))

(deftest fetch-one-with-hint-fails
  (with-test-mongo
    (is (thrown? IllegalArgumentException
                 (fetch-one :test_col :hint "key_1")))))

(deftest fetch-one-with-explain-fails
  (with-test-mongo
    (is (thrown? IllegalArgumentException
                 (fetch-one :test_col :explain? true)))))

(deftest fetch-one-with-limit-fails
  (with-test-mongo
    (is (thrown? IllegalArgumentException
                 (fetch-one :test_col :limit 1)))))

(deftest fetch-one-with-options-fails
  (with-test-mongo
    (is (thrown? IllegalArgumentException
                 (fetch-one :test_col :options [:notimeout])))))

(deftest fetch-with-only
  (with-test-mongo
    (let [data {:_id 10 :foo "clever" :bar "filter"}
          id (:_id data)]
      (insert! :with-only data)
      (are [data-keys select-clause] (= (select-keys data data-keys)
                                        (fetch-one :with-only :only select-clause))
           [:_id :foo] [:foo]
           [:foo :bar] {:_id false}
           [:_id :bar] {:foo false}))))

(deftest fetch-with-hint-fails-correctly-on-bad-args
  (with-test-mongo
    (testing "vector has wrong types"
      (is (thrown? IllegalArgumentException
                   (fetch :t :where {:id 10} :hint ["key1" "key2"])))
      (is (thrown? IllegalArgumentException
                   (fetch :t :where {:id 10} :hint [["key1" 2] "key2"]))))
    (testing "invalid type"
      (is (thrown? IllegalArgumentException
                   (fetch :t :where {:id 10} :hint 5))))))

(deftest fetch-with-hint-works-on-valid-type
  (with-test-mongo
    (add-index! :t [:key1 :key2])
    (add-index! :t [:key1 [:key2 -1]])
    (add-index! :t [:key1 [:key2 1]])
    (insert! :t {:key1 1})

    (is (fetch :t :where {:key1 1} :hint [:key1 :key2]))
    (is (fetch :t :where {:key1 1} :hint [:key1 [:key2 -1]]))
    (is (fetch :t :where {:key1 1} :hint [:key1 [:key2 1]]))))

(deftest fetch-with-hint-changes-index
  (with-test-mongo
    (let [version (-> *mongo-config*
                      :mongo
                      (.getDB test-db)
                      (.command "buildInfo")
                      (.getString "version"))
          mongo2? (-> version (.startsWith "2"))
          mongo3? (-> version (.startsWith "3"))]

      ;; only 1 versions
      (is (or mongo2? mongo3?))
      (is (not (and mongo2? mongo3?)))
      (insert! :test_col {:key1 1 :key2 2})

      (add-index! :test_col [:key1]) ;; index1
      (add-index! :test_col [:key1 :key2]) ;; index 2
      (add-index! :test_col [[:key1 -1]]) ;; index3
      (add-index! :test_col [:key1 [:key2 -1]]) ;; index 4

      (testing "index1"
        (let [plan (-> (fetch :test_col :where {:key1 1} :explain? true :hint "key1_1"))]
          (when mongo2?
            (is (= "BtreeCursor key1_1" (-> plan :cursor))))
          (when mongo3?
            (is (= "key1_1" (-> plan :queryPlanner :winningPlan :inputStage :indexName))))))

      (testing "index1 seq"
        (let [plan (-> (fetch :test_col :where {:key1 1} :explain? true :hint [:key1]))]
          (when mongo2?
            (is (= "BtreeCursor key1_1" (-> plan :cursor))))
          (when mongo3?
            (is (= "key1_1" (-> plan :queryPlanner :winningPlan :inputStage :indexName))))))

      (testing "index2"
        (let [plan (-> (fetch :test_col :where {:key1 1} :explain? true :hint "key1_1_key2_1"))]
          (when mongo2?
            (is (= "BtreeCursor key1_1_key2_1" (-> plan :cursor))))
          (when mongo3?
            (is (= "key1_1_key2_1" (-> plan :queryPlanner :winningPlan :inputStage :indexName))))))

      (testing "index2 seq"
        (let [plan (-> (fetch :test_col :where {:key1 1} :explain? true :hint [:key1 :key2]))]
          (when mongo2?
            (is (= "BtreeCursor key1_1_key2_1" (-> plan :cursor))))
          (when mongo3?
            (is (= "key1_1_key2_1" (-> plan :queryPlanner :winningPlan :inputStage :indexName))))))

      (testing "index3"
        (let [plan (-> (fetch :test_col :where {:key1 1} :explain? true :hint "key1_-1"))]
          (when mongo2?
            (is (= "BtreeCursor key1_-1" (-> plan :cursor))))
          (when mongo3?
            (is (= "key1_-1" (-> plan :queryPlanner :winningPlan :inputStage :indexName))))))

      (testing "index3 seq"
        (let [plan (-> (fetch :test_col :where {:key1 1} :explain? true :hint [[:key1 -1]]))]
          (when mongo2?
            (is (= "BtreeCursor key1_-1" (-> plan :cursor))))
          (when mongo3?
            (is (= "key1_-1" (-> plan :queryPlanner :winningPlan :inputStage :indexName))))))

      (testing "index4"
        (let [plan (-> (fetch :test_col :where {:key1 1} :explain? true :hint "key1_1_key2_-1"))]
          (when mongo2?
            (is (= "BtreeCursor key1_1_key2_-1" (-> plan :cursor))))
          (when mongo3?
            (is (= "key1_1_key2_-1" (-> plan :queryPlanner :winningPlan :inputStage :indexName))))))

      (testing "index4 seq"
        (let [plan (-> (fetch :test_col :where {:key1 1} :explain? true :hint [:key1 [:key2 -1]]))]
          (when mongo2?
            (is (= "BtreeCursor key1_1_key2_-1" (-> plan :cursor))))
          (when mongo3?
            (is (= "key1_1_key2_-1" (-> plan :queryPlanner :winningPlan :inputStage :indexName)))))))))


(deftest fetch-by-id-of-any-type
  (with-test-mongo
    (insert! :by-id {:_id "Blarney" :val "Stone"})
    (insert! :by-id {:_id 300 :val "warriors"})
    (is (= "Stone" (:val (fetch-by-id :by-id "Blarney"))))
    (is (= "warriors" (:val (fetch-by-id :by-id 300))))))

(deftest explain-works
  (with-test-mongo
    (insert! :users {:name "Alice"})
    (insert! :users {:name "Bob"})
    (insert! :users {:name "Carol"})
    (is (map? (fetch :users :where {:name "Bob"} :explain? true)))))

(deftest fetch-by-ids-of-any-type
  (with-test-mongo
    (insert! :by-ids {:_id "Blarney" :val "Stone"})
    (insert! :by-ids {:_id 300 :val "warriors"})
    (is (= #{"Stone" "warriors"} (set (map :val (fetch-by-ids :by-ids ["Blarney" 300])))))))

(deftest eager-ref-fetching
  (let [fetch-eagerly       (with-ref-fetching fetch)
        fetch-eagerly-by-id (with-ref-fetching fetch-by-id)
        command-eagerly     (with-ref-fetching command)]
    (with-test-mongo
      (insert! :users {:_id "js" :name "John Smith" :email "jsmith@foo.bar"})
      (insert! :users {:_id "jd" :name "Jane Doe"   :email "jdoe@foo.bar"})
      (insert! :posts {:_id "p1"
                       :user (db-ref :users "js")
                       :comment "great site!"
                       :location [-10.001 -20.001]})
      (insert! :posts {:_id "p2"
                       :user (db-ref :users "jd")
                       :comment "I agree..."
                       :location [10.001 20.001]})
      (add-index! :posts [[:location "2d"]])

      ;; leave db-refs alone, assumes manual, lazy fetching
      (is (db-ref? (-> (fetch :posts :where {:comment "great site!"}) first :user)))
      (is (db-ref? (-> (fetch :posts :where {:comment "I agree..."})  first :user)))

      ;; eagerly fetch db-refs
      (is (map?           (-> (fetch-eagerly :posts :where {:comment "great site!"}) first :user)))
      (is (= "John Smith" (-> (fetch-eagerly :posts :where {:comment "great site!"}) first :user :name)))
      (is (map?           (-> (fetch-eagerly :posts :where {:comment "I agree..."})  first :user)))
      (is (= "Jane Doe"   (-> (fetch-eagerly :posts :where {:comment "I agree..."})  first :user :name)))

      ;; the decorator works on existing retrieval fns
      (is (db-ref? (:user (fetch-by-id         :posts "p1"))))
      (is (map?    (:user (fetch-eagerly-by-id :posts "p1"))))

      ;; it also works on seq results
      (is (db-ref? (-> (fetch :posts)         first :user)))
      (is (map?    (-> (fetch-eagerly :posts) first :user)))

      ;; and on database commands
      (let [earth-radius (* 6378 1000) ; in meters
            radians      (fn [meters]
                           (float (/ meters earth-radius)))
            cmd          {:geoNear     :posts
                          :near        [10 20]
                          :spherical   true
                          :maxDistance (radians 1000)}
            lazy-result  (command cmd)
            eager-result (command-eagerly cmd)]
        (is (db-ref?      (-> lazy-result  :results first :obj :user)))
        (is (map?         (-> eager-result :results first :obj :user)))
        (is (= "Jane Doe" (-> eager-result :results first :obj :user :name)))))))

`(deftest databases-test
  (with-test-mongo
    (let [test-db2 "congomongotestdb-part-deux"]

      (is (= test-db (.getName (*mongo-config* :db)))
          "default DB exists")
      (set-database! test-db2)

      (is (= test-db2 (.getName (*mongo-config* :db)))
          "changed DB exists")
      (drop-database! test-db2))))

(defn make-points! []
  (println "slow insert of 10000 points:")
  (time
   (doseq [x (range 100)
           y (range 100)]
     (insert! :points {:x x :y y}))))

(deftest slow-insert-and-fetch
  (with-test-mongo
    (make-points!)
    (is (= (* 100 100) (fetch-count :points)))
    (is (= (fetch-count :points
                        :where {:x 42}) 100))))

(deftest destroy
  (with-test-mongo
    (make-points!)
    (let [point-id (:_id (fetch-one :points))]
      (destroy! :points
                {:_id point-id})
      (is (= (fetch-count :points) (dec (* 100 100))))
      (is (= nil (fetch-one :points
                            :where {:_id point-id}))))))

(deftest update-one
  (with-test-mongo
    (make-points!)
    (let [point-id (:_id (fetch-one :points))]
      (update! :points
               {:_id point-id}
               {:x "suffusion of yellow"})
      (is (= (:x (fetch-one :points
                            :where {:_id point-id}))
             "suffusion of yellow")))))


(deftest test-distinct-values
  (with-test-mongo
    (insert! :distinct {:genus "Pan" :species "troglodytes" :common-name "chimpanzee"})
    (insert! :distinct {:genus "Pan" :species "pansicus" :common-name "bonobo"})
    (insert! :distinct {:genus "Homo" :species "sapiens" :common-name "human"})
    (insert! :distinct {:genus "Homo" :species "floresiensis" :common-name "hobbit"})

    (is (= (set (distinct-values :distinct "genus"))
           #{"Pan" "Homo"}))
    (is (= (set (distinct-values :distinct "common-name"))
           #{"chimpanzee" "bonobo" "human" "hobbit"}))
    (is (= (set (distinct-values :distinct "species" :where {:genus "Pan"}))
           #{"troglodytes" "pansicus"}))
    (is (= (set (distinct-values :distinct "species" :where "{\"genus\": \"Pan\"}" :from :json))
           #{"troglodytes" "pansicus"}))
    (let [json (distinct-values :distinct "genus" :as :json)]
      ;; I don't think you can influence the order in which distinct results are returned,
      ;; so just check both possibilities
      (is (or (= (read-str json) ["Pan", "Homo"])
              (= (read-str json) ["Homo", "Pan"]))))))


;; ;; mass insert chokes on excessively large inserts
;; ;; will need to implement some sort of chunking algorithm

(deftest mass-insert
  (with-test-mongo
    (println "mass insert of 10000 points")
    (time
     (mass-insert! :points
                   (for [x (range 100) y (range 100)]
                     {:x x
                      :y y
                      :z (* x y)})))
    (is (= (* 100 100)
           (fetch-count :points))
        "mass-insert okay")))

(deftest insert-for-side-effects-only
  (with-test-mongo
    (is (nil? (insert! :beers {:beer "Franziskaner" :wheaty true} :to nil)))))

(deftest basic-indexing
  (with-test-mongo
    (make-points!)
    (add-index! :points [:x])
    (is (some #(= (into {} (% "key")) {"x" 1})
              (get-indexes :points)))))

(defn- get-index
  "Retrieve an index, either by name or by key vector"
  [coll index]
  (let [selector (if (vector? index)
                   (fn [i]
                     (= (get i "key")
                        (coerce-index-fields index)))
                   (fn [i]
                     (= (get i "name")
                        index)))]
    (first (filter selector (get-indexes coll)))))


(deftest complex-indexing
  (with-test-mongo
    (add-index! :testing-indexes [:a :b :c])
    (let [auto-generated-index-name "a_1_b_1_c_1"
          actual-index (get (get-index :testing-indexes auto-generated-index-name)
                            "key")
          expected-index (doto (BasicDBObject.)
                           (.put "a" 1)
                           (.put "b" 1)
                           (.put "c" 1))]
      (is (= (.toString actual-index) (.toString expected-index))))

    (add-index! :testing-indexes [:a [:b -1] :c])
    (let [auto-generated-index-name "a_1_b_-1_c_1"
          actual-index (get (get-index :testing-indexes auto-generated-index-name)
                            "key")
          expected-index (doto (BasicDBObject.)
                           (.put "a" 1)
                           (.put "b" -1)
                           (.put "c" 1))]
      (is (= (.toString actual-index) (.toString expected-index))))))

(deftest sparse-indexing
  (with-test-mongo
    (add-index! :sparse-index-coll [:a] :unique true :sparse true)
    (set-write-concern *mongo-config* :acknowledged)
    (insert! :sparse-index-coll {:a "foo"})
    (insert! :sparse-index-coll {})
    (try
      (insert! :sparse-index-coll {})
      (is true)
      (catch DuplicateKeyException e
        (is false "Unable to insert second document without the sparse index key")))
    (is (thrown? DuplicateKeyException
                 (insert! :sparse-index-coll {:a "foo"})))
    (set-write-concern *mongo-config* :unacknowledged)))

(deftest partial-indexing
  (with-test-mongo
    (let [db-version (version test-db)]
      (when (not (or (.startsWith db-version "2")
                     (.startsWith db-version "3.0")))
        (add-index! :partial-index-coll [:a] :unique true :partial-filter-expression {:b {:$gt 5}})
        (set-write-concern *mongo-config* :acknowledged)
        (insert! :partial-index-coll {:a "foo" :b 10})
        (insert! :partial-index-coll {:a "foo" :b 1})
        (try
          (insert! :partial-index-coll {:a "foo" :b 2})
          (is true)
          (catch DuplicateKeyException e
            (is false "Unable to insert second document with fields not matching unique partial index")))
        (is (thrown? DuplicateKeyException
                     (insert! :partial-index-coll {:a "foo" :b 6})))
        (set-write-concern *mongo-config* :unacknowledged)))))

(deftest index-name
  (with-test-mongo
    (let [coll :test-index-name
          index "customIndexName"]
      (add-index! coll [:foo :bar :baz] :name index)
      (is (= {"foo" 1 "bar" 1 "baz" 1}
             (get (get-index coll index) "key"))))))

(deftest test-delete-index
  (with-test-mongo
    (let [test-collection :testing-indexes
          index-name "test_index"
          index-key [:c :b [:a -1]]]
      ;; Test using keys
      (is (nil? (get-index test-collection index-key)))
      (add-index! test-collection index-key)
      (is (get-index test-collection index-key))
      (drop-index! test-collection index-key)
      (is (nil? (get-index test-collection index-key)))

      ;; Test using names
      (is (nil? (get-index test-collection index-name)))
      (add-index! test-collection index-key :name index-name)
      (is (get-index test-collection index-name))
      (drop-index! test-collection index-name)
      (is (nil? (get-index test-collection index-name))))))


(defrecord Foo [a b])

(deftest can-insert-records-as-maps
  (with-test-mongo
    (insert! :foos (Foo. 1 2))
    (let [found (fetch-one :foos)]
      (is (= 1 (:a found)))
      (is (= 2 (:b found))))))

(deftest insert-returns-id
  (with-test-mongo
    (let [ret (insert! :foos {:a 1 :b 2})]
      (is (map? ret))
      (is (= (:a ret) 1))
      (is (= (:b ret) 2))
      (is (:_id ret)))))

(deftest gridfs-insert-and-fetch
  (with-test-mongo
    (is (empty? (fetch-files :testfs)))
    (let [f (insert-file! :testfs (.getBytes "toasted")
                          :filename "muffin" :contentType "food/breakfast")]
      (is (= "muffin" (:filename f)))
      (is (= "food/breakfast" (:contentType f)))
      (is (= 7 (:length f)))
      (is (= nil (fetch-one-file :testfs :where {:filename "monkey"})))
      (is (= f (fetch-one-file :testfs :where {:filename "muffin"})))
      (is (= f (fetch-one-file :testfs :where {:contentType "food/breakfast"})))
      (is (= (list f) (fetch-files :testfs))))))

(deftest gridfs-destroy
  (with-test-mongo
    (insert-file! :testfs (.getBytes "banana") :filename "lunch")
    (destroy-file! :testfs {:filename "lunch"})
    (is (empty? (fetch-files :testfs)))))

(deftest gridfs-insert-with-metadata
  (with-test-mongo
    (let [f (insert-file! :testfs (.getBytes "nuts")
                          :metadata {:calories 50, :opinion "tasty"})]
      (is (= "tasty" (get-in f [:metadata :opinion])))
      (is (= f (fetch-one-file :testfs :where { :metadata.opinion "tasty" }))))))

(deftest gridfs-insert-with-id
  (with-test-mongo
    (let [file-id (ObjectId.)
          f (insert-file! :testfs (.getBytes "nuts")
                          :_id file-id)]
      (is (= file-id (get f :_id)))
      (is (= f (fetch-one-file :testfs :where { :_id file-id }))))))

(deftest gridfs-write-file-to
  (with-test-mongo
    (let [f (insert-file! :testfs (.getBytes "banana"))]
      (let [o (java.io.ByteArrayOutputStream.)]
        (write-file-to :testfs f o)
        (is (= "banana" (str o)))))))

(deftest gridfs-stream-from
  (with-test-mongo
    (let [f (insert-file! :testfs (.getBytes "plantain"))
          stream (stream-from :testfs f)
          data (slurp stream)]
      (is (= "plantain" data)))))

(defn- gen-tempfile []
  (let [tmp (doto
                (java.io.File/createTempFile "test" ".data")
              (.deleteOnExit))]
    (with-open [w (java.io.FileOutputStream. tmp)]
      (doseq [i (range 2048)]
        (.write w (int (rem i 255)))))
    tmp))

(deftest gridfs-test-insert-different-data-types
  (with-test-mongo
    (let [^java.io.File file (gen-tempfile)]
      (insert-file! :filefs file)
      (insert-file! :filefs (java.io.FileInputStream. file))
      (insert-file! :filefs (.getBytes "data"))
      (is (= 3 (count (fetch-files :filefs)))))))

(deftest test-roundtrip-vector
  (with-test-mongo
    (insert! :stuff {:name "name" :vector [ "foo" "bar"]})
    (let [return (fetch-one :stuff :where {:name "name"})]
      (is (vector? (:vector return))))))


(deftest test-roundtrip-keywords
  (with-test-mongo
    (insert! :stuff {:name "name" :some-map {:myns/this 1
                                             :thatns/this 4}})
    (let [return (fetch-one :stuff :where {:name "name"})]
      (is (= 1 (get-in return [:some-map :myns/this]))
          (= 4 (get-in return [:some-map :thatns/this])) ))))

;; Note: with Clojure 1.3.0, 1.0 != 1 and the JS stuff returns floating point numbers instead
;; of integers so I've changed the tests to use floats in the expected values - except for the
;; 1000000 value which _does_ come back as an integer! -- Sean Corfield

(deftest test-map-reduce
  (with-test-mongo
    (insert! :mr {:fruit "bananas" :count 1})
    (insert! :mr {:fruit "bananas" :count 2})
    (insert! :mr {:fruit "plantains" :count 3})
    (insert! :mr {:fruit "plantains" :count 2})
    (insert! :mr {:fruit "pineapples" :count 4})
    (insert! :mr {:fruit "pineapples" :count 2})
    (let [mapfn
          "function(){
              emit(this.fruit, {count: this.count});
          }"
          mapfn-with-scope
          "function(){
              emit((adj + ' ' + this.fruit), {count: this.count});
          }"
          reducefn
          "function(key, values){
              var total = 0;
              for ( var i=0; i<values.length; i++ ){
                  total += values[i].count;
              }
              return { count : total };
          }"
          target-collection :monkey-shopping-list]
      ;; See that the base case works
      (is (= (map-reduce :mr mapfn reducefn target-collection)
             (seq [{:_id "bananas" :value {:count 3.0}}
                   {:_id "pineapples" :value {:count 6.0}}
                   {:_id "plantains" :value {:count 5.0}}])))
      ;; Make sure we get the collection name back, too
      (is (= (map-reduce :mr mapfn reducefn target-collection :output :collection)
             target-collection))

      ;; Test the new (>= MongoDB 1.8) MapReduce output options
      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ;; Replace existing data in target collection
      (drop-coll! target-collection)
      (insert! target-collection {:dummy-data true}) ;; we should not find this!
      (is (= (map-reduce :mr mapfn reducefn {:replace target-collection})
             (seq [{:_id "bananas" :value {:count 3.0}}
                   {:_id "pineapples" :value {:count 6.0}}
                   {:_id "plantains" :value {:count 5.0}}])))

      ;; Merge data in the target collection
      (drop-coll! target-collection)
      (insert! target-collection {:_id "macadamia nuts" :value {:count 1000000}})
      (is (= (map-reduce :mr mapfn reducefn {:merge target-collection})
             (seq [{:_id "macadamia nuts" :value {:count 1000000}}
                   {:_id "bananas" :value {:count 3.0}}
                   {:_id "pineapples" :value {:count 6.0}}
                   {:_id "plantains" :value {:count 5.0}}])))

      ;; Reduce with existing data
      (drop-coll! target-collection)
      (insert! target-collection {:_id "bananas" :value {:count 10}})
      (is (= (map-reduce :mr mapfn reducefn {:reduce target-collection})
             (seq [{:_id "bananas" :value {:count 13.0}}
                   {:_id "pineapples" :value {:count 6.0}}
                   {:_id "plantains" :value {:count 5.0}}])))

      ;; inline data (no output collection)
      (is (= (map-reduce :mr mapfn reducefn {:inline 1})
             (seq [{:_id "bananas" :value {:count 3.0}}
                   {:_id "pineapples" :value {:count 6.0}}
                   {:_id "plantains" :value {:count 5.0}}])))

      ;; inline output ignores if you ask for an output collection name instead
      (is (= (map-reduce :mr mapfn reducefn {:inline 1} :output :collection)
             (seq [{:_id "bananas" :value {:count 3.0}}
                   {:_id "pineapples" :value {:count 6.0}}
                   {:_id "plantains" :value {:count 5.0}}])))

      ;; Check limit
      (is (= (map-reduce :mr mapfn reducefn target-collection :limit 2)
             (seq [{:_id "bananas" :value {:count 3.0}}])))
      ;; Check sort
      ;; sort requires an index to work?
      (add-index! :mr [:fruit])
      (is (= (map-reduce :mr mapfn reducefn target-collection :sort {:fruit -1} :limit 2)
             (seq [{:_id "plantains" :value {:count 5.0}}])))
      ;; check query
      (is (= (map-reduce :mr mapfn reducefn target-collection :query {:fruit "pineapples"})
             (seq [{:_id "pineapples" :value {:count 6.0}}])))
      ;; check finalize
      (is (= (map-reduce :mr mapfn reducefn target-collection
                         :finalize "function(key, value){return 'There are ' + value.count + ' ' + key}")
             (seq [{:_id "bananas" :value "There are 3 bananas"}
                   {:_id "pineapples" :value "There are 6 pineapples"}
                   {:_id "plantains" :value "There are 5 plantains"}])))
      ;; check scope
      (is (= (map-reduce :mr mapfn-with-scope reducefn target-collection
                         :scope {:adj "tasty"})
             (seq [{:_id "tasty bananas" :value {:count 3.0}}
                   {:_id "tasty pineapples" :value {:count 6.0}}
                   {:_id "tasty plantains" :value {:count 5.0}}])))
      )))

(deftest test-server-eval
  (with-test-mongo
    (is (= (server-eval
            "
function ()
{
 function square (n)
 {
  return n*n;                           ;
  }
 return square (25);
 }
") 625.0))))

(deftest dup-key-exception-works
  (with-test-mongo
    (println "unique index / write concern interaction")
    (add-index! :dup-key-coll [:unique-col] :unique true)
    (let [obj {:unique-col "some string"}]
      ;; first one, should succeed
      (try
        (insert! :dup-key-coll obj :write-concern :acknowledged)
        (is true)
        (catch Exception e
          (is false "Unable to insert first document")))
      (try
        (insert! :dup-key-coll obj :write-concern :acknowledged)
        (is false "Did not get a duplicate key error")
       (catch Exception e
         (is true)))
      ;; with write concern of :unacknowledged, this should succeed too
      ;; because the error is not detected / thrown
      (try
        (insert! :dup-key-coll obj :write-concern :unacknowledged)
        (is true)
        (catch Exception e
          (is false "Unable to insert duplicate with :acknowledged concern"))))))

(deftest test-group-command
  (with-test-mongo
    (drop-coll! :test-group )
    (insert! :test-group {:fruit "bananas" :count 1})
    (insert! :test-group {:fruit "bananas" :count 2})
    (insert! :test-group {:fruit "plantains" :count 3})
    (insert! :test-group {:fruit "plantains" :count 2})
    (insert! :test-group {:fruit "pineapples" :count 4})
    (insert! :test-group {:fruit "pineapples" :count 2})
    (let [reduce-count-fn "function(obj,prev){prev.count+=obj.count;}"
          bananas-count (group :test-group :key [:fruit ] :initial {:count 0} :reducefn reduce-count-fn
        :where {:fruit "bananas"})
          all-count-keyf (group :test-group :keyfn "function(obj){return {'category':obj.fruit};}"
        :initial {:count 0} :reducefn reduce-count-fn
        :finalizefn "function(obj) {return {'items':obj.count,'fruit':obj.category};}")]
      (is (= bananas-count
            [{:fruit "bananas", :count 3.0}]))
      (is (= all-count-keyf
            [{:items 3.0, :fruit "bananas"} {:items 5.0, :fruit "plantains"} {:items 6.0, :fruit "pineapples"}])))))


(deftest test-read-preference
  (let [f-tag (TagSet. (Tag. "location" "nearby"))
        r-tags (TagSet. (Tag. "rack" "bottom"))]
    (are [expected type tags] (= expected (apply read-preference (cons type tags)))
      (ReadPreference/nearest) :nearest nil
      (ReadPreference/nearest f-tag) :nearest [{:location "nearby"}]
      (ReadPreference/nearest [f-tag r-tags]) :nearest [{:location "nearby"} {:rack :bottom}]
      (ReadPreference/primary) :primary nil
      (ReadPreference/primaryPreferred) :primary-preferred nil
      (ReadPreference/primaryPreferred f-tag) :primary-preferred [{:location "nearby"}]
      (ReadPreference/primaryPreferred [f-tag r-tags]) :primary-preferred [{:location "nearby"} {:rack :bottom}]
      (ReadPreference/secondary) :secondary nil
      (ReadPreference/secondary f-tag) :secondary [{:location "nearby"}]
      (ReadPreference/secondary [f-tag r-tags]) :secondary [{:location "nearby"} {:rack :bottom}]
      (ReadPreference/secondaryPreferred) :secondary-preferred nil
      )))

(deftest get-and-set-collection-read-preference
  (with-test-mongo
    (create-collection! :with-preferences )
    (set-collection-read-preference! :with-preferences :nearest )
    (is (= (ReadPreference/nearest) (get-collection-read-preference :with-preferences )))))

(deftest get-and-set-collection-write-concern
  (with-test-mongo
    (create-collection! :with-write-concern )
    (set-collection-write-concern! :with-write-concern :unacknowledged )
    (is (= WriteConcern/UNACKNOWLEDGED (get-collection-write-concern :with-write-concern )))))

(deftest index-names-include-asc-desc-information
  ;; See https://jira.mongodb.org/browse/JAVA-1971
  (with-test-mongo
    (when (not (-> (version test-db) (.startsWith "2.4.")))
      (insert! :test_col {:key1 1})

      ;; Add key1 asc index
      (.createIndex (get-coll :test_col)
                    (coerce {:key1 1} [:clojure :mongo])
                    (coerce {:unique false :sparse false :background false} [:clojure :mongo]))

      ;; Add key1 desc index, fails until JAVA-1971 is fixed
      (.createIndex (get-coll :test_col)
                    (coerce {:key1 -1} [:clojure :mongo])
                    (coerce {:unique false :sparse false :background false} [:clojure :mongo]))

      (let [index-info (coerce (get-indexes :test_col)
                               [:mongo :clojure]
                               :many? true) ]
        ;; default _id index and the two created above
        (is (= 3 (count index-info)))
        (is (set/subset? #{"key1_1" "key1_-1"}
                         (set (map :name index-info))))))))
