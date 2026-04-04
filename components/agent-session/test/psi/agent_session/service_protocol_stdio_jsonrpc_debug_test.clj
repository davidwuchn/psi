(ns psi.agent-session.service-protocol-stdio-jsonrpc-debug-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.service-protocol-stdio-jsonrpc :as stdio-jsonrpc]
   [psi.agent-session.services :as services]
   [psi.agent-session.test-support :as test-support]))

(def ^:private jsonrpc-echo-script
  (str
   "python3 -u - <<'PY'\n"
   "import sys, json\n"
   "while True:\n"
   "    headers = {}\n"
   "    while True:\n"
   "        line = sys.stdin.buffer.readline()\n"
   "        if not line:\n"
   "            sys.exit(0)\n"
   "        if line in (b'\\r\\n', b'\\n'):\n"
   "            break\n"
   "        k, v = line.decode('utf-8').split(':', 1)\n"
   "        headers[k.strip().lower()] = v.strip()\n"
   "    n = int(headers.get('content-length', '0'))\n"
   "    body = sys.stdin.buffer.read(n)\n"
   "    msg = json.loads(body.decode('utf-8'))\n"
   "    out = {'jsonrpc': '2.0', 'id': msg['id'], 'result': {'echo': msg.get('method'), 'id': msg['id']}}\n"
   "    payload = json.dumps(out).encode('utf-8')\n"
   "    sys.stdout.buffer.write((f'Content-Length: {len(payload)}\\r\\n\\r\\n').encode('utf-8'))\n"
   "    sys.stdout.buffer.write(payload)\n"
   "    sys.stdout.buffer.flush()\n"
   "PY"))

(deftest attach-runtime-produces-notification-observable-state-test
  (testing "reader thread can observe inbound frames from a live subprocess"
    (let [ctx {:service-registry (services/create-registry)}
          cwd (test-support/temp-cwd)
          _   (services/ensure-service-in!
               ctx
               {:key [:jsonrpc-debug cwd]
                :type :subprocess
                :spec {:command ["bash" "-lc" jsonrpc-echo-script]
                       :cwd cwd
                       :transport :stdio
                       :protocol :json-rpc}})
          svc0 (services/service-in ctx [:jsonrpc-debug cwd])
          runtime (stdio-jsonrpc/create-jsonrpc-runtime svc0)
          _   (services/set-service-runtime-fns-in! ctx [:jsonrpc-debug cwd] runtime)
          svc (services/service-in ctx [:jsonrpc-debug cwd])]
      (try
        ((:send-fn svc) {"jsonrpc" "2.0" "id" "req-1" "method" "initialize" "params" {}})
        (Thread/sleep 200)
        (is (seq @(:debug-atom svc)))
        (is (some #(= :send (:event %)) @(:debug-atom svc)))
        (finally
          (when-let [close-fn (:close-fn svc)]
            (close-fn))
          (services/stop-service-in! ctx [:jsonrpc-debug cwd]))))))
