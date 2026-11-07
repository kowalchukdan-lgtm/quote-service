import { expect, Page, test } from '@playwright/test';
import path from 'node:path';

/** The golden-set PDFs; the backend's fake model answers each one as the golden set expects. */
const GOLDEN = path.resolve(__dirname, '../../src/test/resources/extraction/golden');

async function upload(page: Page, sample: string): Promise<void> {
  await page.goto('/');
  await page.getByLabel('Broker document (PDF, up to 10 MB)').setInputFiles(path.join(GOLDEN, `${sample}.pdf`));
  await page.getByRole('button', { name: 'Read document' }).click();
  await expect(page).toHaveURL(/\/extractions\/ext_[0-9A-Z]{26}$/);
}

/** yyyy-MM-dd, `days` from today in local time, as a date input takes it. */
function daysFromToday(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

test('a missing revenue is filled in, confirmed and quoted', async ({ page }) => {
  await upload(page, 'sample-04-coastal-taco-stand');

  const revenue = page.getByLabel('Annual revenue (USD)');
  await expect(revenue).toHaveValue('');
  await expect(revenue).toHaveAccessibleDescription(/Not found in the document/);
  const confirm = page.getByRole('button', { name: 'Confirm submission' });
  await expect(confirm).toBeDisabled();

  await revenue.fill('650000');
  // The golden effective date stops being confirmable in December 2026, so move it, as a
  // reviewer would (see the golden-set README).
  await page.getByLabel('Effective date').fill(daysFromToday(30));
  await confirm.click();

  await page.getByRole('link', { name: 'Continue to the submission to get a quote' }).click();
  await expect(page).toHaveURL(/\/submissions\/sub_[0-9A-Z]{26}$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Coastal Taco Stand LLC' })).toBeVisible();

  await page.getByRole('button', { name: 'Get quote' }).click();
  await expect(page.getByRole('heading', { name: 'Quote' })).toBeVisible();
  await expect(page.getByText('Quoted', { exact: true })).toBeVisible();
  await expect(page.getByText('Annual premium: $1,170.00')).toBeVisible();
});

test('a document with text aimed at automated systems is flagged for the reviewer', async ({ page }) => {
  await upload(page, 'sample-02-brightline-code-works');

  await expect(page.getByRole('alert')).toContainText('The document contains text aimed at automated systems.');
  await expect(page.getByLabel('Business name')).toHaveValue('Brightline Code Works Inc.');
});

test('reloading the review page loads the extraction again', async ({ page }) => {
  await upload(page, 'sample-01-keystone-pipe-heating');
  const url = page.url();
  await expect(page.getByLabel('Business name')).toHaveValue(/Keystone/);

  await page.reload();

  expect(page.url()).toBe(url);
  await expect(page.getByLabel('Business name')).toHaveValue(/Keystone/);
  await expect(page.getByRole('button', { name: 'Confirm submission' })).toBeEnabled();
});
