import { expect, test } from "@playwright/test";

/**
 * Smoke E2E: a brand-new install reaches the empty events list, the
 * user creates an event, adds two participants, records an expense,
 * and sees the balance reflect the split. No sync involved — that's
 * gated on a live Cloudflare Worker and is covered by the unit-level
 * transport tests instead.
 *
 * Runs across Chromium, WebKit, and the `iPhone 14` device emulator
 * (which matters because PWA install gestures and the camera-capture
 * input behave differently in Mobile Safari).
 */
test("create event, add participants, add expense, see balance", async ({
  page,
}) => {
  await page.goto("/");

  // Empty state -> create event. The events list shows a top "Nouvel
  // événement" button plus a circular FAB at the bottom-right with
  // the same accessible name; click the first one and then confirm in
  // the dialog (the dialog reuses the same label on its primary
  // action).
  await page.getByRole("button", { name: "Nouvel événement" }).first().click();
  const createDialog = page.getByRole("dialog");
  await createDialog.getByLabel("Nom de l'événement").fill("Test trip");
  await createDialog
    .getByRole("button", { name: "Nouvel événement" })
    .click();

  // Routed to /event/:id with the four tabs.
  await expect(page.getByRole("tab", { name: "Participants" })).toBeVisible();

  // Add two participants.
  await page.getByRole("tab", { name: "Participants" }).click();
  for (const name of ["Alice", "Bob"]) {
    await page
      .getByRole("button", { name: "Ajouter un participant" })
      .first()
      .click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Nom").fill(name);
    await dialog.getByRole("button", { name: "Ajouter un participant" }).click();
    // Wait for the dialog to close before opening the next one,
    // otherwise the two same-labelled buttons coexist briefly.
    await expect(dialog).toBeHidden();
  }
  await expect(page.getByText("Alice")).toBeVisible();
  await expect(page.getByText("Bob")).toBeVisible();

  // Add a 30€ expense paid by Alice, split equally.
  await page.getByRole("tab", { name: "Dépenses" }).click();
  await page
    .getByRole("button", { name: "Ajouter une dépense" })
    .first()
    .click();
  await page.getByLabel("Titre").fill("Dîner");
  await page.getByLabel("Montant (€)").fill("30");
  await page.getByRole("button", { name: "Enregistrer" }).click();

  // Back on the event screen: the expense is listed.
  await expect(page.getByText("Dîner")).toBeVisible();

  // Balances tab: Alice +15€, Bob −15€.
  await page.getByRole("tab", { name: "Balances" }).click();
  await expect(page.getByText("+15,00 €")).toBeVisible();
  await expect(page.getByText("-15,00 €")).toBeVisible();
});
