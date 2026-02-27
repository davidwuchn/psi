(ns extensions.hello-ext-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.hello-ext :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(deftest init-registers-command-and-handler-test
  (testing "hello extension registers command + session handler"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/hello_ext.clj"})]
      (sut/init api)
      (is (= "hello"
             (get-in @state [:commands "hello" :name])))
      (is (= 1 (count (get-in @state [:handlers "session_switch"])))))))
