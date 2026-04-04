(ns psi.agent-session.service-protocol-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.service-protocol :as protocol]
   [psi.agent-session.services :as services]))

(defn- make-ctx-with-service [sent*]
  (let [ctx {:service-registry (services/create-registry)}
        reg (:state (:service-registry ctx))]
    (swap! reg assoc-in [:services [:svc "repo"]]
           {:key [:svc "repo"]
            :status :running
            :transport :stdio
            :send-fn (fn [payload] (swap! sent* conj payload))})
    ctx))

(deftest send-service-request-test
  (testing "send-service-request! writes payload through service send-fn"
    (let [sent*  (atom [])
          ctx    (make-ctx-with-service sent*)
          result (protocol/send-service-request!
                  ctx [:svc "repo"]
                  {:request-id "r1"
                   :payload {:x 1}
                   :timeout-ms 123})]
      (is (= [{:x 1}] @sent*))
      (is (= [:svc "repo"] (:service-key result)))
      (is (= "r1" (:request-id result)))
      (is (= 123 (:timeout-ms result))))))

(deftest send-service-request-includes-synchronous-response-test
  (testing "request-fn response is surfaced when present"
    (let [ctx {:service-registry (services/create-registry)}
          reg (:state (:service-registry ctx))]
      (swap! reg assoc-in [:services [:svc "repo"]]
             {:key [:svc "repo"]
              :status :running
              :transport :stdio
              :request-fn (fn [_]
                            {:payload {"result" []}
                             :is-error false})})
      (let [result (protocol/send-service-request!
                    ctx [:svc "repo"]
                    {:request-id "r2"
                     :payload {:x 2}
                     :timeout-ms 200})]
        (is (= {"result" []}
               (get-in result [:response :payload])))))))

(deftest send-service-notification-test
  (testing "send-service-notification! does not require request id"
    (let [sent*  (atom [])
          ctx    (make-ctx-with-service sent*)
          result (protocol/send-service-notification! ctx [:svc "repo"] {:y 2})]
      (is (= [{:y 2}] @sent*))
      (is (= [:svc "repo"] (:service-key result)))
      (is (= {:y 2} (:payload result))))))

(deftest jsonrpc-helpers-test
  (testing "jsonrpc-request! preserves protocol fields"
    (let [sent* (atom [])
          ctx   (make-ctx-with-service sent*)]
      (protocol/jsonrpc-request! ctx [:svc "repo"] {:id "1" :method "m" :params {:a 1}} {:timeout-ms 321})
      (is (= [{"jsonrpc" "2.0" "id" "1" "method" "m" "params" {:a 1}}]
             @sent*)))))

(deftest jsonrpc-notify-helper-test
  (testing "jsonrpc-notify! preserves protocol fields"
    (let [sent* (atom [])
          ctx   (make-ctx-with-service sent*)]
      (protocol/jsonrpc-notify! ctx [:svc "repo"] {:method "n" :params {:b 2}})
      (is (= [{"jsonrpc" "2.0" "method" "n" "params" {:b 2}}]
             @sent*)))))
