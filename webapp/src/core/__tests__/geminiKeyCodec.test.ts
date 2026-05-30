import { describe, expect, it } from "vitest";
import {
  decodeGeminiKey,
  encodeGeminiKey,
  isGeminiKeyUrl,
} from "@/core/settings/geminiKeyCodec";

describe("geminiKeyCodec", () => {
  it("round-trips with model", () => {
    const url = encodeGeminiKey(
      "AIzaSy_some-key.with/symbols=",
      "gemini-2.5-flash",
    );
    const out = decodeGeminiKey(url);
    expect(out.key).toBe("AIzaSy_some-key.with/symbols=");
    expect(out.model).toBe("gemini-2.5-flash");
  });

  it("round-trips without model", () => {
    const out = decodeGeminiKey(encodeGeminiKey("k", null));
    expect(out.key).toBe("k");
    expect(out.model).toBeNull();
  });

  it("drops blank model", () => {
    const out = decodeGeminiKey(encodeGeminiKey("k", "   "));
    expect(out.model).toBeNull();
  });

  it("rejects blank key on encode", () => {
    expect(() => encodeGeminiKey("   ", null)).toThrow();
  });

  it("rejects missing key on decode", () => {
    expect(() => decodeGeminiKey("fairshare://gemini?model=foo")).toThrow();
  });

  it("recognises the scheme", () => {
    expect(isGeminiKeyUrl("fairshare://gemini?key=x")).toBe(true);
    expect(isGeminiKeyUrl("fairshare://join?event=x")).toBe(false);
    expect(
      isGeminiKeyUrl("https://fairshare-web-bdg.pages.dev/join?event=x"),
    ).toBe(false);
  });

  it("is byte-compatible with the Android URL form", () => {
    // Manually-crafted reference: same encoding rules as Android's
    // URLEncoder.encode(UTF-8).replace("+", "%20"). encodeURIComponent
    // already produces equivalent output for these characters.
    const url = encodeGeminiKey("a b/c?d", "x.y");
    expect(url).toBe("fairshare://gemini?key=a%20b%2Fc%3Fd&model=x.y");
  });
});
