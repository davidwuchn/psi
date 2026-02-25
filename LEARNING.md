# Learning

Accumulated discoveries from ψ evolution.

## 2025-02-24 23:34 - Bootstrap Testing

### λ Testing Infrastructure Works

**Test Command**: `clojure -M:test` (not `-X:test`)
- `-X:test` fails (no :exec-fn defined) 
- `-M:test` succeeds (uses :main-opts with kaocha.runner)

**AI Component Status**: ✓ All tests passing
- psi.ai.core-test: 5 tests, 23 assertions, 0 failures
- Core functionality verified:
  - Stream options validation
  - Message handling 
  - Usage calculation
  - Model validation (Claude/OpenAI)
  - Conversation lifecycle

**Test Configuration**:
- Kaocha runner with documentation reporter
- Test paths: test/ + components/ai/test/
- Integration tests skipped (marked with :integration meta)
- Colorized output enabled

### λ System State Understanding

**Runtime**: JVM Clojure ready
- deps.edn: Polylith AI component + pathom3 + statecharts
- tests.edn: Kaocha configuration
- Latest: AI component integrated (commit 8663e14)

**Architecture Progress**:
- ✓ AI component implemented & tested
- ✓ Allium specs defined  
- ? Engine (statecharts integration)
- ? Query interface (pathom3 integration)
- ? Graph emergence from resolvers

**Next**: System integration beyond component tests