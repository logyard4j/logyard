import { smoke } from "smoque";
import { writeFile } from "node:fs/promises";

smoke.suite("ECS ingestion", async (t) => {
  const root = t.repoRoot();
  await t.step("Index real encoder output and query standard ECS fields", async () => {
    const result = await t.cmd("python3", ["-m", "ecs_tools.verify", root.path()], {
      cwd: root,
      env: { PYTHONPATH: root.path("scripts"), PYTHONDONTWRITEBYTECODE: "1" },
      timeout: "5m", check: false,
    });
    const output = `${result.stdout}\n${result.stderr}`;
    await writeFile(root.path("target/ecs-verify/ingestion.log"), output);
    if (result.exitCode !== 0) t.fail(`ECS ingestion exited ${result.exitCode}:\n${output.slice(-6000)}`);
  });
});
