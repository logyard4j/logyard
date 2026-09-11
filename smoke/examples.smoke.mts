import { smoke } from "smoque";
import { writeFile } from "node:fs/promises";

const scenarios = [
  "slf4j", "opentelemetry", "vertx", "micronaut",
  "spring-boot-3-mvc", "spring-boot-4-mvc",
  "spring-boot-3-webflux", "spring-boot-4-webflux",
  "spring-boot-4-no-actuator", "spring-boot-4-external-config", "spring-boot-4-safe-defaults",
  "spring-boot-4-provider-conflict",
  "quarkus", "quarkus-disabled",
];

// Run sequentially: every scenario refreshes Logyard's shared artifact cache.
smoke.suite("Logyard published JVM examples", async (t) => {
  const root = t.repoRoot();
  const repository = process.env.LOGYARD_RELEASE_TARGET ?? root.path("target/release-bundle");
  const version = process.env.LOGYARD_EXAMPLE_VERSION;
  if (!version) t.fail("Run ./scripts/examples-verify to prepare and verify the release bundle first.");

  for (const scenario of scenarios) {
    await t.step(`${scenario}: build, run, and verify logging`, { continueOnFailure: true }, async () => {
      const result = await t.cmd("python3", [
        "-m", "example_tools.verify", root.path(), repository, version!, "--scenario", scenario,
      ], {
        cwd: root,
        env: { PYTHONPATH: root.path("scripts"), PYTHONDONTWRITEBYTECODE: "1" },
        timeout: "15m",
        check: false,
      });
      const output = `${result.stdout}\n${result.stderr}`;
      await writeFile(root.path("target/examples-verify", `${scenario}.build.log`), output);
      if (result.exitCode !== 0) t.fail(`${scenario} exited ${result.exitCode}:\n${output.slice(-6000)}`);
    });
  }
});
