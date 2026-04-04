(ns psi.agent-session.service-protocol-stdio-jsonrpc-parsing-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.service-protocol-stdio-jsonrpc :as sut])
  (:import
   (java.io ByteArrayInputStream BufferedInputStream)
   (java.nio.charset StandardCharsets)))

(defn- in-stream [s]
  (BufferedInputStream.
   (ByteArrayInputStream. (.getBytes ^String s StandardCharsets/UTF_8))))

(deftest read-jsonrpc-message-test
  (testing "reads one framed json-rpc message"
    (let [body "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"ok\":true}}"
          framed (str "Content-Length: " (count (.getBytes body "UTF-8")) "\r\n\r\n" body)
          result (#'sut/read-jsonrpc-message! (in-stream framed))]
      (is (= {"jsonrpc" "2.0" "id" "1" "result" {"ok" true}}
             (:payload result))))))
