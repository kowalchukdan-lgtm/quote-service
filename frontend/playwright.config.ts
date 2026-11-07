import { defineConfig, devices } from '@playwright/test';

const CI = !!process.env['CI'];

/**
 * End-to-end tests against the real system: the Angular app on `ng serve`, the Spring API and a
 * fresh Postgres (Testcontainers, so Docker must be running). Only the AI model is faked: the
 * backend runs from test code with the golden-set model (see E2eQuoteServiceApplication).
 *
 * Ports 8081 and 4201 keep clear of a normal dev backend (8080) and `ng serve` (4200), which have
 * no fake model. Outside CI, servers already running on those ports are reused.
 */
export default defineConfig({
  testDir: 'e2e',
  forbidOnly: CI,
  retries: 0,
  reporter: CI ? [['github'], ['html', { open: 'never' }]] : [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://localhost:4201',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      name: 'backend',
      cwd: '..',
      command:
        './mvnw -B -q spring-boot:test-run' +
        ' -Dspring-boot.run.main-class=com.kowalchuk.quoteservice.e2e.E2eQuoteServiceApplication' +
        ' -Dspring-boot.run.profiles=e2e -Dspring-boot.run.arguments=--server.port=8081',
      url: 'http://localhost:8081/actuator/health',
      // Covers a cold Maven compile and the first Postgres image pull.
      timeout: 240_000,
      reuseExistingServer: !CI,
      stdout: 'pipe',
    },
    {
      name: 'frontend',
      command: 'npx ng serve --port 4201 --proxy-config proxy.e2e.conf.json',
      url: 'http://localhost:4201',
      timeout: 120_000,
      reuseExistingServer: !CI,
    },
  ],
});
