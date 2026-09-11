#!/usr/bin/env bash
# Shared release helpers. Zolt owns every native publication; Maven contributes only Quarkus artifacts.

LOGYARD_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

logyard_release_fail() {
  printf 'release failed: %s\n' "$1" >&2
  exit 1
}

logyard_zolt() {
  printf '%s\n' "${ZOLT:-$(command -v zolt || printf '%s' "$HOME/.zolt/bin/zolt")}"
}

logyard_require_zolt() {
  local zolt
  zolt="$(logyard_zolt)"
  [[ -x "$zolt" ]] || logyard_release_fail "Zolt was not found at $zolt; run scripts/bootstrap-zolt or set ZOLT"
  printf '%s\n' "$zolt"
}

logyard_complete_jdk() {
  local candidate="$1"
  [[ -n "$candidate" && -x "$candidate/bin/javadoc" ]]
}

logyard_jdk_home() {
  local candidate javac_path
  for candidate in "${ZOLT_JAVA_HOME:-}" "${JAVA_HOME:-}"; do
    if logyard_complete_jdk "$candidate"; then
      printf '%s\n' "$candidate"
      return
    fi
  done
  if [[ -x /usr/libexec/java_home ]]; then
    candidate="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if logyard_complete_jdk "$candidate"; then
      printf '%s\n' "$candidate"
      return
    fi
  fi
  javac_path="$(command -v javac || true)"
  if [[ -n "$javac_path" ]]; then
    candidate="$(cd "$(dirname "$javac_path")/.." && pwd -P)"
    if logyard_complete_jdk "$candidate"; then
      printf '%s\n' "$candidate"
      return
    fi
  fi
  logyard_release_fail 'no JDK with javadoc was found; set ZOLT_JAVA_HOME or JAVA_HOME'
}

logyard_manifest_value() {
  local manifest="$1"
  local section="$2"
  local key="$3"
  sed -n "/^\\[$section\\]/,/^\\[/ { s/^[[:space:]]*[\"]*$key[\"]*[[:space:]]*=[[:space:]]*\"\\([^\"]*\\)\".*$/\\1/p; }" "$manifest" | head -n 1
}

logyard_workspace_members() {
  awk '
    /^members = \[/ { inside = 1; next }
    inside && /^]/ { exit }
    inside && match($0, /"[^"]+"/) { print substr($0, RSTART + 1, RLENGTH - 2) }
  ' "$LOGYARD_ROOT/zolt.toml"
}

logyard_publish_members() {
  local member
  while IFS= read -r member; do
    grep -q '^\[publish' "$LOGYARD_ROOT/$member/zolt.toml" && printf '%s\n' "$member"
  done < <(logyard_workspace_members)
  return 0
}

logyard_test_members() {
  local member
  while IFS= read -r member; do
    awk '
      /^\[build\]$/ { inside = 1; next }
      inside && /^\[/ { exit }
      inside && /^[[:space:]]*test[[:space:]]*=/ { found = 1 }
      END { exit(found ? 0 : 1) }
    ' "$LOGYARD_ROOT/$member/zolt.toml" && printf '%s\n' "$member"
  done < <(logyard_workspace_members)
  return 0
}

logyard_release_version() {
  local expected member actual module
  expected="$(logyard_manifest_value "$LOGYARD_ROOT/modules/logyard-api/zolt.toml" project version)"
  [[ -n "$expected" ]] || logyard_release_fail 'modules/logyard-api/zolt.toml has no project version'
  while IFS= read -r member; do
    actual="$(logyard_manifest_value "$LOGYARD_ROOT/$member/zolt.toml" project version)"
    [[ "$actual" == "$expected" ]] || logyard_release_fail "$member does not use release version $expected"
  done < <(logyard_publish_members)
  for module in extensions/logyard-quarkus/runtime extensions/logyard-quarkus/deployment; do
    grep -Fq "<version>$expected</version>" "$LOGYARD_ROOT/$module/pom.xml" \
      || logyard_release_fail "$module does not use release version $expected"
  done
  printf '%s\n' "$expected"
}

logyard_publish_member_count() {
  logyard_publish_members | wc -l | tr -d ' '
}

logyard_native_jar_records() {
  local member manifest artifact module_name version
  version="$(logyard_release_version)"
  while IFS= read -r member; do
    manifest="$LOGYARD_ROOT/$member/zolt.toml"
    grep -q '^\[bom\]' "$manifest" && continue
    artifact="$(logyard_manifest_value "$manifest" project name)"
    module_name="$(logyard_manifest_value "$manifest" package.manifest Automatic-Module-Name)"
    [[ -n "$artifact" && -n "$module_name" ]] || logyard_release_fail "$member has incomplete native publication metadata"
    printf '%s\t%s\t%s\t3\n' "$artifact" "$module_name" "$version"
  done < <(logyard_publish_members)
  return 0
}

logyard_maven_publication_records() {
  local module artifact module_name version
  version="$(logyard_release_version)"
  for module in extensions/logyard-quarkus/runtime extensions/logyard-quarkus/deployment; do
    artifact="$(awk '/<\/parent>/{ after_parent = 1; next } after_parent && /<artifactId>/ { sub(/.*<artifactId>/, ""); sub(/<\/artifactId>.*/, ""); print; exit }' "$LOGYARD_ROOT/$module/pom.xml")"
    module_name="$(sed -n 's/.*<Automatic-Module-Name>\([^<]*\)<\/Automatic-Module-Name>.*/\1/p' "$LOGYARD_ROOT/$module/pom.xml" | head -n 1)"
    [[ -n "$artifact" && -n "$module_name" ]] || logyard_release_fail "$module has incomplete Maven publication metadata"
    printf '%s\t%s\t%s\t%s\t3\n' "$module" "$artifact" "$module_name" "$version"
  done
}

logyard_group_path() {
  printf '%s\n' "$1" | tr '.' '/'
}

logyard_file_uri() {
  local directory="$1"
  [[ "$directory" != *' '* ]] || logyard_release_fail "Maven repository paths cannot contain spaces: $directory"
  printf 'file://%s\n' "$directory"
}

logyard_require_file() {
  [[ -s "$1" ]] || logyard_release_fail "missing or empty artifact: $1"
}

logyard_native_publication_directory() {
  local member="$1"
  local manifest="$LOGYARD_ROOT/$member/zolt.toml"
  local group artifact version
  group="$(logyard_manifest_value "$manifest" project group)"
  artifact="$(logyard_manifest_value "$manifest" project name)"
  version="$(logyard_release_version)"
  printf '%s/%s/%s/%s\n' "$2" "$(logyard_group_path "$group")" "$artifact" "$version"
}

logyard_copy_native_publication() {
  local member="$1"
  local destination_root="$2"
  local manifest="$LOGYARD_ROOT/$member/zolt.toml"
  local artifact version source destination file
  artifact="$(logyard_manifest_value "$manifest" project name)"
  version="$(logyard_release_version)"
  source="$LOGYARD_ROOT/$member/target"
  destination="$(logyard_native_publication_directory "$member" "$destination_root")"
  mkdir -p "$destination"
  logyard_require_file "$source/publish/$artifact-$version.pom"
  cp "$source/publish/$artifact-$version.pom" "$destination/$artifact-$version.pom"
  if [[ -f "$source/$artifact-$version.jar" ]]; then
    for file in "$artifact-$version.jar" "$artifact-$version-sources.jar" "$artifact-$version-javadoc.jar"; do
      logyard_require_file "$source/$file"
      cp "$source/$file" "$destination/$file"
    done
  fi
  if [[ -f "$source/publish/$artifact-$version-cyclonedx.json" ]]; then
    logyard_require_file "$source/publish/$artifact-$version-cyclonedx.json"
    cp "$source/publish/$artifact-$version-cyclonedx.json" "$destination/$artifact-$version-cyclonedx.json"
  fi
  return 0
}

logyard_copy_maven_publication() {
  local module="$1"
  local artifact="$2"
  local version="$3"
  local destination="$4/com/logyard4j/$artifact/$version"
  local source="$LOGYARD_ROOT/$module/target"
  local file
  mkdir -p "$destination"
  logyard_require_file "$source/publication-pom.xml"
  cp "$source/publication-pom.xml" "$destination/$artifact-$version.pom"
  for file in "$artifact-$version.jar" "$artifact-$version-sources.jar" "$artifact-$version-javadoc.jar"; do
    logyard_require_file "$source/$file"
    cp "$source/$file" "$destination/$file"
  done
  return 0
}

logyard_stage_native_repository() {
  local staging="$1"
  local member
  case "$staging" in
    "$LOGYARD_ROOT"/target/*) ;;
    *) logyard_release_fail "native Maven staging must be under $LOGYARD_ROOT/target: $staging" ;;
  esac
  rm -rf "$staging"
  mkdir -p "$staging"
  while IFS= read -r member; do
    logyard_copy_native_publication "$member" "$staging"
  done < <(logyard_publish_members)
  return 0
}

logyard_assemble_release_bundle() {
  local target="$1"
  local version member module artifact module_name record
  case "$target" in
    "$LOGYARD_ROOT"/target/*|/tmp/*|/private/tmp/*) ;;
    *) logyard_release_fail "release target must be under $LOGYARD_ROOT/target or /tmp: $target" ;;
  esac
  version="$(logyard_release_version)"
  rm -rf "$target"
  mkdir -p "$target"
  while IFS= read -r member; do
    logyard_copy_native_publication "$member" "$target"
  done < <(logyard_publish_members)
  while IFS=$'\t' read -r module artifact module_name record_version record; do
    [[ "$record_version" == "$version" ]] || logyard_release_fail "$module does not use release version $version"
    logyard_copy_maven_publication "$module" "$artifact" "$version" "$target"
  done < <(logyard_maven_publication_records)
  printf '%s\n' "$version" > "$target/VERSION"
  printf 'Release bundle assembled from %s Zolt publications and 2 Maven publications.\n' "$(logyard_publish_member_count)"
}

logyard_hash() {
  local algorithm="$1"
  local file="$2"
  case "$algorithm" in
    md5)
      command -v md5sum >/dev/null 2>&1 && md5sum "$file" | awk '{ print $1 }' || md5 -q "$file"
      ;;
    sha1)
      command -v sha1sum >/dev/null 2>&1 && sha1sum "$file" | awk '{ print $1 }' || shasum -a 1 "$file" | awk '{ print $1 }'
      ;;
    sha256)
      command -v sha256sum >/dev/null 2>&1 && sha256sum "$file" | awk '{ print $1 }' || shasum -a 256 "$file" | awk '{ print $1 }'
      ;;
    *) logyard_release_fail "unsupported checksum algorithm: $algorithm" ;;
  esac
}

logyard_primary_bundle_files() {
  find "$1" -type f \( -name '*.jar' -o -name '*.pom' -o -name '*-cyclonedx.json' \) -print | LC_ALL=C sort
}

logyard_checksum_subjects() {
  {
    logyard_primary_bundle_files "$1"
    find "$1" -type f -name '*.asc' -print
  } | LC_ALL=C sort
}

logyard_write_checksums() {
  local target="$1"
  local artifact algorithm
  while IFS= read -r artifact; do
    for algorithm in md5 sha1 sha256; do
      logyard_hash "$algorithm" "$artifact" > "$artifact.$algorithm"
    done
  done < <(logyard_checksum_subjects "$target")
  return 0
}

logyard_verify_checksums() {
  local artifact algorithm expected actual
  while IFS= read -r artifact; do
    for algorithm in md5 sha1 sha256; do
      expected="$artifact.$algorithm"
      logyard_require_file "$expected"
      actual="$(logyard_hash "$algorithm" "$artifact")"
      [[ "$(tr -d '[:space:]' < "$expected")" == "$actual" ]] \
        || logyard_release_fail "checksum mismatch: $expected"
    done
  done < <(logyard_checksum_subjects "$1")
  return 0
}

logyard_create_central_archive() {
  local bundle="$1"
  local archive="$2"
  local staging artifact relative
  staging="$(mktemp -d "$LOGYARD_ROOT/target/central-archive.XXXXXX")"
  trap 'rm -rf "$staging"' RETURN
  while IFS= read -r artifact; do
    relative="${artifact#"$bundle"/}"
    mkdir -p "$staging/$(dirname "$relative")"
    cp "$artifact" "$staging/$relative"
    touch -t 198001010000 "$staging/$relative"
  done < <(find "$bundle" -type f ! -name VERSION -print | LC_ALL=C sort)
  mkdir -p "$(dirname "$archive")"
  rm -f "$archive"
  (
    cd "$staging"
    find . -type f -print | sed 's#^\./##' | LC_ALL=C sort | zip -X -q "$archive" -@
  )
  printf 'Central Portal bundle created: %s\n' "$archive"
}
