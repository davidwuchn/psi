#!/bin/bash
set -euo pipefail

echo "🧪 Running working component tests individually..."
echo

# Track totals
total_tests=0
total_assertions=0
total_failures=0

# Function to run a component test and extract results
run_component_test() {
    local component=$1
    echo "Testing $component..."
    
    local result=$(bb test:$component 2>&1 | tail -1)
    echo "  $result"
    
    # Extract numbers (basic regex parsing)
    if [[ $result =~ ([0-9]+)\ tests,\ ([0-9]+)\ assertions,\ ([0-9]+)\ failures ]]; then
        local tests=${BASH_REMATCH[1]}
        local assertions=${BASH_REMATCH[2]}
        local failures=${BASH_REMATCH[3]}
        
        total_tests=$((total_tests + tests))
        total_assertions=$((total_assertions + assertions))
        total_failures=$((total_failures + failures))
    fi
    echo
}

# Run each working component
run_component_test "agent-core"
run_component_test "query" 
run_component_test "memory"
run_component_test "engine"
run_component_test "ai"
run_component_test "recursion"

echo "🏁 TOTAL RESULTS:"
if [[ $total_failures -eq 0 ]]; then
    echo "✅ $total_tests tests, $total_assertions assertions, $total_failures failures."
else
    echo "❌ $total_tests tests, $total_assertions assertions, $total_failures failures."
fi

# Exit with failure code if any failures
exit $total_failures