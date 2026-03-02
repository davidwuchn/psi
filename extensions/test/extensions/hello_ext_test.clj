(ns extensions.hello-ext-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.hello-ext :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(deftest init-registers-command-handler-and-tools-test
  (testing "hello extension registers commands, session handler, and demo tools"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/hello_ext.clj"})]
      (sut/init api)
      (is (= "hello"
             (get-in @state [:commands "hello" :name])))
      (is (= "hello-plan"
             (get-in @state [:commands "hello-plan" :name])))
      (is (= "hello_upper"
             (get-in @state [:tools "hello_upper" :name])))
      (is (= "hello_wrap"
             (get-in @state [:tools "hello_wrap" :name])))
      (is (= 1 (count (get-in @state [:handlers "session_switch"])))))))
