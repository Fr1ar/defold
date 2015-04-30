(ns internal.value-test
  (:require [clojure.core.match :as match]
            [clojure.test :refer :all]
            [dynamo.graph :as g]
            [dynamo.graph.test-support :refer :all]
            [dynamo.types :as t]
            [internal.transaction :as it]))

(def ^:dynamic *calls*)

(defn tally [node fn-symbol]
  (swap! *calls* update-in [(g/node-id node) fn-symbol] (fnil inc 0)))

(defn get-tally [node fn-symbol]
  (get-in @*calls* [(g/node-id node) fn-symbol] 0))

(defmacro expect-call-when [node fn-symbol & body]
  `(let [calls-before# (get-tally ~node ~fn-symbol)]
     ~@body
     (is (= (inc calls-before#) (get-tally ~node ~fn-symbol)))))

(defmacro expect-no-call-when [node fn-symbol & body]
  `(let [calls-before# (get-tally ~node ~fn-symbol)]
     ~@body
     (is (= calls-before# (get-tally ~node ~fn-symbol)))))

(g/defnode CacheTestNode
  (input first-name String)
  (input last-name  String)
  (input operand    String)

  (property scalar t/Str)

  (output uncached-value  String
          (g/fnk [this scalar]
                 (tally this 'produce-simple-value)
                 scalar))

  (output expensive-value String :cached
          (g/fnk [this]
                 (tally this 'compute-expensive-value)
                 "this took a long time to produce"))

  (output nickname        String :cached
          (g/fnk [this first-name]
                 (tally this 'passthrough-first-name)
                 first-name))

  (output derived-value   String :cached
          (g/fnk [this first-name last-name]
                 (tally this 'compute-derived-value)
                 (str first-name " " last-name)))

  (output another-value   String :cached
          (g/fnk [this]
                 "this is distinct from the other outputs")))

(defn build-sample-project
  [world]
  (g/tx-nodes-added
   (g/transact
    (g/make-nodes world
                  [name1     [CacheTestNode :scalar "Jane"]
                   name2     [CacheTestNode :scalar "Doe"]
                   combiner  CacheTestNode
                   expensive CacheTestNode]
                  (g/connect name1 :uncached-value combiner :first-name)
                  (g/connect name2 :uncached-value combiner :last-name)
                  (g/connect name1 :uncached-value expensive :operand)))))

(defn with-function-counts
  [f]
  (binding [*calls* (atom {})]
    (f)))

(use-fixtures :each with-function-counts)

(deftest project-cache
  (with-clean-system
    (let [[name1 name2 combiner expensive] (build-sample-project world)]
      (testing "uncached values are unaffected"
        (is (= "Jane" (g/node-value name1 :uncached-value)))))))

(deftest caching-avoids-computation
  (testing "cached values are only computed once"
    (with-clean-system
      (let [[name1 name2 combiner expensive] (build-sample-project world)]
        (is (= "Jane Doe" (g/node-value combiner :derived-value)))
        (expect-no-call-when combiner 'compute-derived-value
                             (doseq [x (range 100)]
                               (g/node-value combiner :derived-value))))))

  (testing "modifying inputs invalidates the cached value"
    (with-clean-system
      (let [[name1 name2 combiner expensive] (build-sample-project world)]
        (is (= "Jane Doe" (g/node-value combiner :derived-value)))
        (expect-call-when combiner 'compute-derived-value
                          (g/transact (it/update-property name1 :scalar (constantly "John") []))
                          (is (= "John Doe" (g/node-value combiner :derived-value)))))))

  (testing "transmogrifying a node invalidates its cached value"
    (with-clean-system
      (let [[name1 name2 combiner expensive] (build-sample-project world)]
        (is (= "Jane Doe" (g/node-value combiner :derived-value)))
        (expect-call-when combiner 'compute-derived-value
                          (g/transact (it/become name1 (g/construct CacheTestNode)))
                          (is (= "Jane Doe" (g/node-value combiner :derived-value)))))))

  (testing "cached values are distinct"
    (with-clean-system
      (let [[name1 name2 combiner expensive] (build-sample-project world)]
        (is (= "this is distinct from the other outputs" (g/node-value combiner :another-value)))
        (is (not= (g/node-value combiner :another-value) (g/node-value combiner :expensive-value))))))

  (testing "cache invalidation only hits dependent outputs"
    (with-clean-system
      (let [[name1 name2 combiner expensive] (build-sample-project world)]
        (is (= "Jane" (g/node-value combiner :nickname)))
        (expect-call-when combiner 'passthrough-first-name
                          (g/transact (it/update-property name1 :scalar (constantly "Mark") []))
                          (is (= "Mark" (g/node-value combiner :nickname))))
        (expect-no-call-when combiner 'passthrough-first-name
                             (g/transact (it/update-property name2 :scalar (constantly "Brandenburg") []))
                             (is (= "Mark" (g/node-value combiner :nickname)))
                             (is (= "Mark Brandenburg" (g/node-value combiner :derived-value))))))))

(g/defnode OverrideValueNode
  (input overridden t/Str)
  (output output t/Str (g/fnk [overridden] overridden))
  (output foo    t/Str (g/fnk [an-input] an-input)))

(defn build-override-project
  [world]
  (let [nodes (tx-nodes
               (g/make-node world OverrideValueNode)
               (g/make-node world CacheTestNode :scalar "Jane"))
        [override jane]  nodes]
    (g/transact
     (g/connect jane :uncached-value override :overridden))
    nodes))

(deftest invalid-resource-values
  (with-clean-system
    (let [[override jane] (build-override-project world)]
      (testing "requesting a non-existent label throws"
        (is (thrown? clojure.lang.ExceptionInfo (g/node-value override :aint-no-thang)))))))

(deftest update-sees-in-transaction-value
  (with-clean-system
    (let [[node]            (tx-nodes (g/make-node world OverrideValueNode :name "a project" :int-prop 0))
          after-transaction (g/transact
                             (concat
                              (g/update-property node :int-prop inc)
                              (g/update-property node :int-prop inc)
                              (g/update-property node :int-prop inc)
                              (g/update-property node :int-prop inc)))]
      (is (= 4 (:int-prop (g/refresh node)))))))

(defn- cache-peek [cache node-id label]
  (get @cache [node-id label]))

(defn- cached? [cache node-id label]
  (not (nil? (cache-peek cache node-id label))))

(g/defnode SelfCounter
  (property call-counter t/Int (default 0))

  (output plus-1 t/Int :cached
          (g/fnk [self call-counter]
                 (g/transact (g/update-property self :call-counter inc))
                 (inc call-counter))))

(deftest intermediate-uncached-values-are-not-cached
  (with-clean-system
    (let [[node]       (tx-nodes (g/make-node world SelfCounter :first-call true))
          node-id      (:_id node)]
      (g/node-value node :plus-1)
      (is (cached? cache node-id :plus-1))
      (is (not (cached? cache node-id :self)))
      (is (not (cached? cache node-id :call-counter))))))

(g/defnode Source
  (property constant t/Keyword))

(g/defnode ValuePrecedence
  (property overloaded-output-input-property t/Keyword (default :property))
  (output   overloaded-output-input-property t/Keyword (g/fnk [] :output))

  (input    overloaded-input-property t/Keyword)
  (output   overloaded-input-property t/Keyword (g/fnk [overloaded-input-property] overloaded-input-property))

  (property the-property t/Keyword (default :property))

  (output   output-using-overloaded-output-input-property t/Keyword (g/fnk [overloaded-output-input-property] overloaded-output-input-property))

  (input    eponymous t/Keyword)
  (output   eponymous t/Keyword (g/fnk [eponymous] eponymous)))


(deftest node-value-precedence
  (with-clean-system
    (let [[node s1] (tx-nodes (g/make-node world ValuePrecedence)
                              (g/make-node world Source :constant :input))]
      (g/transact
       (concat
        (g/connect s1 :constant node :overloaded-input-property)
        (g/connect s1 :constant node :eponymous)))
      (is (= :output   (g/node-value node :overloaded-output-input-property)))
      (is (= :input    (g/node-value node :overloaded-input-property)))
      (is (= :property (g/node-value node :the-property)))
      (is (= :output   (g/node-value node :output-using-overloaded-output-input-property)))
      (is (= :input    (g/node-value node :eponymous))))))

(deftest invalidation-across-graphs
  (with-clean-system
    (let [project-graph (g/make-graph! :history true)
          view-graph    (g/make-graph! :volatility 100)
          [content-node aux-node] (tx-nodes (g/make-node project-graph CacheTestNode :scalar "Snake")
                                            (g/make-node project-graph CacheTestNode :scalar "Plissken"))
          [view-node]    (tx-nodes (g/make-node view-graph CacheTestNode))]
      (g/transact
       [(g/connect content-node :scalar view-node :first-name)
        (g/connect aux-node     :scalar view-node :last-name)])

      (expect-call-when
       view-node 'compute-derived-value
       (is (= "Snake Plissken" (g/node-value view-node :derived-value))))

      (g/transact (g/set-property aux-node :scalar "Solid"))

      (expect-call-when
       view-node 'compute-derived-value
       (is (= "Snake Solid" (g/node-value view-node :derived-value))))

      (g/transact
       [(g/disconnect     aux-node :scalar view-node :last-name)
        (g/disconnect content-node :scalar view-node :first-name)
        (g/connect        aux-node :scalar view-node :first-name)
        (g/connect    content-node :scalar view-node :last-name)])

      (expect-call-when
       view-node 'compute-derived-value
       (is (= "Solid Snake" (g/node-value view-node :derived-value))))

      (expect-no-call-when
       view-node 'compute-derived-value
       (is (= "Solid Snake" (g/node-value view-node :derived-value)))))))

(g/defnode SubstitutingInputsNode
  (input unary-no-sub     t/Int)
  (input multi-no-sub    [t/Int])
  (input unary-with-sub   t/Int  :substitute 99)
  (input multi-with-sub  [t/Int] :substitute 4848)

  (output unary-no-sub    t/Int  (g/fnk [unary-no-sub] unary-no-sub))
  (output multi-no-sub   [t/Int] (g/fnk [multi-no-sub] multi-no-sub))
  (output unary-with-sub  t/Int  (g/fnk [unary-with-sub] unary-with-sub))
  (output multi-with-sub [t/Int] (g/fnk [multi-with-sub] multi-with-sub)))

(g/defnode ConstantNode
  (property value t/Any)
  (output source t/Int (g/fnk [value] value)))

(defn arrange-error-value-call
  [world label connected? source-sends]
  (let [[receiver const] (tx-nodes (g/make-node world SubstitutingInputsNode)
                                   (g/make-node world ConstantNode :value source-sends))]
    (when connected?
      (g/transact (g/connect const :source receiver label)))
    (g/node-value receiver label)))

(deftest error-value-replacement
  (with-clean-system
    (are [label connected? source-sends expected-pfn-val]
      (= expected-pfn-val (arrange-error-value-call world label connected? source-sends))
      ;; output-label connected? source-sends  expected-pfn
      :unary-no-sub   false      :dontcare     nil
      :multi-no-sub   false      :dontcare     '()
      :unary-with-sub false      :dontcare     nil
      :multi-with-sub false      :dontcare     '()

      :unary-no-sub   true       nil           nil
      :unary-with-sub true       nil           nil

      :multi-no-sub   true       1             '(1)
      :multi-with-sub true       42            '(42)
      :multi-no-sub   true       '(nil 1 2 3)  '[(nil 1 2 3)]
      :multi-with-sub true       '(nil 1 2 3)  '[(nil 1 2 3)]

      :unary-no-sub   true       (g/error)      (g/error)
      :unary-with-sub true       (g/error)      99

      :multi-no-sub   true       (g/error)      [(g/error)]
      :multi-with-sub true       (g/error)      [4848])))
