import { describe, expect, it } from "vitest";
import { AdvancedWorkbenchController } from "./advanced";
import type { BackendState } from "./types";

const detectedBackend: BackendState = {
  mode: "ghidra-headless",
  health: "detected",
  version: "12.1.2",
  launcher: "/opt/ghidra/support/analyzeHeadless",
  capabilities: [],
};

describe("advanced workbench controller", () => {
  it("keeps historical trace staleness distinct from emulator writes", () => {
    const controller = new AdvancedWorkbenchController();
    const historical = controller.selectSnapshot(118);
    expect(historical.status).toBe("past-snapshot");
    expect(historical.staleMemory).toBe(true);
    expect(historical.writesEnabled).toBe(false);

    const emulator = controller.forkEmulator();
    expect(emulator.mode).toBe("emulator");
    expect(emulator.writesEnabled).toBe(true);
    expect(controller.stepEmulator(1).snapshot).toBe(119);
  });

  it("separates read expressions, mutation approval, and blocked process access", () => {
    const controller = new AdvancedWorkbenchController();
    expect(controller.evaluate(":where").status).toBe("success");
    expect(controller.evaluate("functions(\"packet\")").status).toBe("success");
    expect(controller.evaluate("setName(currentFunction, \"parse_frame\")")).toMatchObject({
      status: "approval-required",
      requiredCapabilities: ["program.write"],
    });
    expect(controller.evaluate("new ProcessBuilder(\"sh\").start()")).toMatchObject({
      status: "blocked",
      requiredCapabilities: ["process.exec"],
    });
    expect(controller.evaluate("getFunctionAt(currentAddress)").status).toBe("backend-unavailable");
  });

  it("does not confuse a detected distribution with a connected adapter", () => {
    const controller = new AdvancedWorkbenchController(detectedBackend);
    expect(controller.setExtensionEnabled("bsim-client", true, ["program.read", "network"])).toMatchObject({
      enabled: false,
      status: expect.stringContaining("not connected"),
    });
    expect(controller.evaluate("getFunctionAt(currentAddress)").status).toBe("backend-unavailable");
  });
});
