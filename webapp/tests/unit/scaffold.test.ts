import { describe, it, expect } from "vitest";

// Smoke test — keeps `vitest run` exit-zero until real test files land
// in the next port commits (crypto KATs, lamport, invitation codec, …).
describe("scaffold", () => {
  it("vitest runs", () => {
    expect(1 + 1).toBe(2);
  });
});
