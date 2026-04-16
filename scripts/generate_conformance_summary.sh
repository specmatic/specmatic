#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/generate_conformance_summary.sh <xml-dir> [--output <file>]

Generate a markdown summary for Gradle/JUnit XML reports in <xml-dir>.

Arguments:
  <xml-dir>          Directory containing TEST-*.xml files
  --output <file>    Write the summary to <file> instead of stdout

Example:
  scripts/generate_conformance_summary.sh conformance-tests/build/test-results/test
  scripts/generate_conformance_summary.sh conformance-tests/build/test-results/test --output "$GITHUB_STEP_SUMMARY"
EOF
}

if [[ $# -lt 1 ]]; then
  usage >&2
  exit 1
fi

xml_dir=""
output_file=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output)
      if [[ $# -lt 2 ]]; then
        echo "Missing value for --output" >&2
        exit 1
      fi
      output_file="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
    *)
      if [[ -n "$xml_dir" ]]; then
        echo "Unexpected argument: $1" >&2
        usage >&2
        exit 1
      fi
      xml_dir="$1"
      shift
      ;;
  esac
done

if [[ -z "$xml_dir" ]]; then
  echo "Missing xml directory argument" >&2
  usage >&2
  exit 1
fi

if [[ ! -d "$xml_dir" ]]; then
  echo "XML directory not found: $xml_dir" >&2
  exit 1
fi

shopt -s nullglob
xml_files=("$xml_dir"/TEST-*.xml)
shopt -u nullglob

expected_failures_file="$(mktemp)"
output_tmp_file="$(mktemp)"

cleanup() {
  rm -f "$expected_failures_file" "$output_tmp_file"
}

trap cleanup EXIT

total_tests=0

for xml_file in "${xml_files[@]}"; do
  test_count="$(
    awk '
      /<testsuite / && /tests="/ {
        line = $0
        sub(/^.*tests="/, "", line)
        sub(/".*$/, "", line)
        print line + 0
        exit
      }
      END {
        if (NR == 0) {
          print 0
        }
      }
    ' "$xml_file"
  )"
  total_tests=$((total_tests + test_count))

  full_classname="$(
    awk '
      /classname="/ {
        line = $0
        sub(/^.*classname="/, "", line)
        sub(/".*$/, "", line)
        print line
        exit
      }
    ' "$xml_file"
  )"
  test_class="${full_classname##*.}"

  awk -v test_class="$test_class" '
    /Expected failure:/ {
      reason = $0
      sub(/^.*Expected failure: /, "", reason)
      next
    }
    /Test:/ && reason != "" {
      test_method = $0
      sub(/^.*Test: /, "", test_method)
      printf("| %s | %s | %s |\n", test_class, test_method, reason)
      reason = ""
    }
  ' "$xml_file" >> "$expected_failures_file"
done

expected_count="$(wc -l < "$expected_failures_file" | tr -d ' ')"

{
  echo "### Conformance Test Summary"
  echo
  echo "### Expected Failures"
  echo
  echo "| Test Class | Test Method | Reason |"
  echo "|------------|-------------|--------|"
  cat "$expected_failures_file"
  echo
  echo "**Test Statistics**"
  echo "- Total Tests: $total_tests"
  echo "- Expected Failures: $expected_count"
  echo
} > "$output_tmp_file"

if [[ -n "$output_file" ]]; then
  cat "$output_tmp_file" >> "$output_file"
else
  cat "$output_tmp_file"
fi
