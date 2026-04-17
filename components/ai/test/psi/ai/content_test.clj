(ns psi.ai.content-test
  (:require [clojure.test :refer [deftest is testing]]
            [psi.ai.content :as content]))

(deftest content-text-test
  (testing "text content returns text"
    (is (= "hello"
           (content/content-text {:kind :text :text "hello"}))))

  (testing "structured content joins text and thinking block text in order"
    (is (= "hello\nplan"
           (content/content-text {:kind :structured
                                  :blocks [{:kind :text :text "hello"}
                                           {:kind :thinking :text "plan"}
                                           {:kind :tool-call :name "read" :input {}}]}))))

  (testing "whole message input is supported"
    (is (= "hello"
           (content/content-text {:role :assistant
                                  :content {:kind :text :text "hello"}})))))

(deftest assistant-content-parts-test
  (let [msg {:role :assistant
             :content {:kind :structured
                       :blocks [{:kind :text :text "a"}
                                {:kind :thinking :text "b"}
                                {:kind :tool-call :name "read" :input {"path" "README.md"}}]}}
        parts (content/assistant-content-parts msg)]
    (is (= 3 (count (:blocks parts))))
    (is (= [{:kind :text :text "a"}] (:text-blocks parts)))
    (is (= [{:kind :thinking :text "b"}] (:thinking-blocks parts)))
    (is (= [{:kind :tool-call :name "read" :input {"path" "README.md"}}]
           (:tool-call-blocks parts)))))
