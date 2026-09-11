import { smoke } from "smoque";
import { writeFile } from "node:fs/promises";

smoke.suite("Logging provider comparison", async (t) => {
  const root = t.repoRoot();
  await t.step("Verify isolated providers, equal records, and reconciled file completion", async () => {
    const result = await t.cmd("python3", ["-m", "comparison_tools.run", root.path(), "--smoke"], {
      cwd: root,
      env: { PYTHONPATH: root.path("scripts"), PYTHONDONTWRITEBYTECODE: "1" },
      timeout: "8m", check: false,
    });
    const output = `${result.stdout}\n${result.stderr}`;
    await writeFile(root.path("target/benchmark-compare/smoke.log"), output);
    if (result.exitCode !== 0) t.fail(`Comparison verification exited ${result.exitCode}:\n${output.slice(-6000)}`);
  });
});
