(ns slopp.cli-test.fixture
  "A namespace that declares one command, for the discovery test to scan.

  A real `defn` with real name metadata rather than a synthesized var: the
  thing under test is exactly what `ns-publics` reports for a command an app
  would actually write, and a hand-built var would let the scan pass over a
  shape no author produces.

  `helper` is here on purpose — a public fn that is NOT a command, so the test
  can show that the MARKER is the declaration and being public is not.")

(defn ^{:cli/command "greet"
        :cli/doc "Say hello to someone."
        :cli/args [:catn [:who :string]]}
  greet
  "The command handler: writes its answer to the injected stream, returns its
  status. Nothing here touches an ambient stream, which is what `cli-direct-stdio`
  polices."
  [ctx args]
  (.write ^java.io.Writer (:cli/out ctx) (str "hello " (:who args) "\n"))
  nil)

(defn ^{:unused-ok "the NEGATIVE half of the discovery fixture — a public fn carrying no :cli/command, so the scan has something it must correctly ignore. Calling it would destroy what it is for"}
  helper
  "A public fn that is not a command, so the scan has something to correctly ignore."
  [x]
  x)
