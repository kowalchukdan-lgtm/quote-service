import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Extraction, Problem, provideApi } from '../api';
import { API_BASE_PATH } from '../app.config';
import { ExtractionPlaceholder } from './extraction-placeholder';

// Shaped like the contract's ExtractionDraft example.
const draft: Extraction = {
  id: 'ext_123',
  status: 'DRAFT',
  document: { fileName: 'coastal-taco-stand-application.pdf', sizeBytes: 3011, sha256: 'a'.repeat(64) },
  draft: { businessName: 'Coastal Taco Stand LLC' },
  flags: [{ field: '/annualRevenue', reason: 'MISSING' }],
  evidence: [],
  missingFields: ['/annualRevenue'],
  model: 'gpt-5-mini-2025-08-07',
  createdAt: '2026-11-07T17:02:41Z',
};

describe('ExtractionPlaceholder', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ExtractionPlaceholder],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideApi(API_BASE_PATH)],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  // Not whenStable(): the resource's open request keeps the app unstable until the test flushes it.
  function render(id: string) {
    const fixture = TestBed.createComponent(ExtractionPlaceholder);
    fixture.componentRef.setInput('extractionId', id);
    fixture.detectChanges();
    return fixture;
  }

  it('loads the extraction through the generated client, on a relative URL', async () => {
    const fixture = render('ext_123');

    // Relative, not https://api.example.com: the dev proxy and same-origin hosting depend on it.
    const req = http.expectOne('/v1/extractions/ext_123');
    expect(req.request.method).toBe('GET');
    req.flush(draft);
    await fixture.whenStable();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="status"]')?.textContent).toBe('DRAFT');
    expect(el.textContent).toContain('coastal-taco-stand-application.pdf');
    expect(el.textContent).toContain('/annualRevenue');
  });

  it('shows the Problem Details title when the API returns an error', async () => {
    const fixture = render('ext_missing');

    const problem: Problem = {
      type: 'https://api.example.com/problems/not-found',
      title: 'Resource not found',
      status: 404,
    };
    http.expectOne('/v1/extractions/ext_missing').flush(problem, { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();

    const alert = (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]');
    expect(alert?.textContent).toContain('404 Resource not found');
  });
});
