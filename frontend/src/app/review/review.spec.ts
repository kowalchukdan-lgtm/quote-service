import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { Extraction, Problem, Submission, SubmissionRequest, ValidationProblem, provideApi } from '../api';
import { API_BASE_PATH } from '../app.config';
import { Review } from './review';

// Shaped like the contract's ExtractionDraft example (revenue missing), plus review flags.
function draftExtraction(overrides: Partial<Extraction> = {}): Extraction {
  return {
    id: 'ext_123',
    status: 'DRAFT',
    document: { fileName: 'coastal-taco-stand-application.pdf', sizeBytes: 3011, sha256: 'a'.repeat(64) },
    draft: {
      businessName: 'Coastal Taco Stand LLC',
      address: { line1: '1402 E 7th Ave', city: 'Tampa', region: 'FL', postalCode: '33605', country: 'US' },
      classOfBusiness: { naicsCode: '722513' },
      requestedLimit: {
        perOccurrence: { amount: '1000000.00', currency: 'USD' },
        aggregate: { amount: '2000000.00', currency: 'USD' },
      },
      effectiveDate: '2026-12-15',
    },
    flags: [
      { field: '/annualRevenue', reason: 'MISSING', note: 'The application says financials are to follow.' },
      { field: '/address/city', reason: 'APPROXIMATE', note: 'Written as "Tampa area".' },
    ],
    evidence: [{ field: '/classOfBusiness/naicsCode', sourceText: 'NAICS code: 722513', confidence: 'HIGH' }],
    missingFields: ['/annualRevenue'],
    model: 'gpt-5-mini-2025-08-07',
    createdAt: '2026-11-07T17:02:41Z',
    ...overrides,
  };
}

const submission: Submission = {
  id: 'sub_456',
  businessName: 'Coastal Taco Stand LLC',
  address: { line1: '1402 E 7th Ave', city: 'Tampa', region: 'FL', postalCode: '33605', country: 'US' },
  classOfBusiness: { naicsCode: '722513', description: 'Limited-Service Restaurants' },
  annualRevenue: { amount: '1250000.00', currency: 'USD' },
  requestedLimit: {
    perOccurrence: { amount: '1000000.00', currency: 'USD' },
    aggregate: { amount: '2000000.00', currency: 'USD' },
  },
  effectiveDate: '2026-12-15',
  createdAt: '2026-11-07T17:09:15Z',
};

function confirmedExtraction(): Extraction {
  return draftExtraction({ status: 'CONFIRMED', submissionId: 'sub_456', confirmedAt: '2026-11-07T17:09:15Z' });
}

describe('Review', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<Review>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Review],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideApi(API_BASE_PATH), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  // Not whenStable(): an open request keeps the app unstable until the test flushes it.
  function render(id = 'ext_123') {
    fixture = TestBed.createComponent(Review);
    el = fixture.nativeElement as HTMLElement;
    fixture.componentRef.setInput('extractionId', id);
    fixture.detectChanges();
  }

  // Resources deliver a flushed response asynchronously; let them settle, then re-render.
  async function refresh() {
    for (let i = 0; i < 3; i++) {
      await new Promise((resolve) => setTimeout(resolve));
      fixture.detectChanges();
    }
  }

  async function load(extraction: Extraction) {
    render(extraction.id);
    http.expectOne(`/v1/extractions/${extraction.id}`).flush(extraction);
    await refresh();
  }

  const field = (key: string) => el.querySelector<HTMLElement>(`[data-field="${key}"]`)!;
  const control = (key: string) => el.querySelector<HTMLInputElement & HTMLSelectElement>(`#field-${key}`)!;
  const confirmButton = () =>
    [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Confirm submission'))!;

  async function type(key: string, value: string) {
    const c = control(key);
    c.value = value;
    c.dispatchEvent(new Event(c.tagName === 'SELECT' ? 'change' : 'input'));
    c.dispatchEvent(new Event('blur'));
    await refresh();
  }

  function describedText(key: string): string {
    const ids = control(key).getAttribute('aria-describedby')?.split(' ') ?? [];
    return ids.map((id) => el.querySelector(`#${id}`)?.textContent ?? '').join(' ');
  }

  async function confirmWithRevenue(revenue = '1,250,000') {
    await type('annualRevenue', revenue);
    confirmButton().click();
    await refresh();
    return http.expectOne('/v1/extractions/ext_123/confirm');
  }

  describe('loading', () => {
    it('loads the extraction through the generated client, on a relative URL', async () => {
      render();
      expect(el.textContent).toContain('Loading');
      const req = http.expectOne('/v1/extractions/ext_123');
      expect(req.request.method).toBe('GET');
      req.flush(draftExtraction());
      await refresh();
      expect(el.textContent).toContain('coastal-taco-stand-application.pdf');
    });

    it('shows the Problem Details title when the API returns an error', async () => {
      render('ext_missing');
      const problem: Problem = { type: 'https://api.example.com/problems/not-found', title: 'Resource not found', status: 404 };
      http.expectOne('/v1/extractions/ext_missing').flush(problem, { status: 404, statusText: 'Not Found' });
      await refresh();
      expect(el.querySelector('[role="alert"]')?.textContent).toContain('404 Resource not found');
    });
  });

  describe('draft review', () => {
    it('pre-fills the form from the draft', async () => {
      await load(draftExtraction());

      expect(control('businessName').value).toBe('Coastal Taco Stand LLC');
      expect(control('line1').value).toBe('1402 E 7th Ave');
      expect(control('line2').value).toBe('');
      expect(control('city').value).toBe('Tampa');
      expect(control('region').value).toBe('FL');
      expect(control('postalCode').value).toBe('33605');
      expect(control('naicsCode').value).toBe('722513');
      expect(control('annualRevenue').value).toBe('');
      expect(control('requestedLimit').value).toBe('1000000.00/2000000.00');
      expect(control('effectiveDate').value).toBe('2026-12-15');
    });

    it('labels every field', async () => {
      await load(draftExtraction());
      for (const c of el.querySelectorAll('input, select')) {
        expect(el.querySelector(`label[for="${c.id}"]`), c.id).not.toBeNull();
      }
    });

    it('places flags and evidence on the field their pointer names', async () => {
      await load(draftExtraction());

      expect(field('annualRevenue').classList).toContain('missing');
      expect(describedText('annualRevenue')).toContain('Not found in the document');
      expect(describedText('annualRevenue')).toContain('financials are to follow');

      expect(field('city').classList).toContain('review');
      expect(describedText('city')).toContain('Approximate');
      expect(describedText('city')).toContain('Tampa area');

      // /classOfBusiness/naicsCode lands on the NAICS field.
      expect(describedText('naicsCode')).toContain('NAICS code: 722513');
      expect(describedText('naicsCode')).toContain('Confidence: HIGH');

      expect(field('businessName').classList).not.toContain('missing');
      expect(field('businessName').classList).not.toContain('review');
    });

    it('shows a whole-document flag as a banner at the top', async () => {
      await load(
        draftExtraction({
          flags: [{ field: '', reason: 'SUSPECTED_INSTRUCTION', note: 'Asks the reader to approve the risk.' }],
        }),
      );

      const banner = el.querySelector('.banner[role="alert"]');
      expect(banner?.textContent).toContain('text aimed at automated systems');
      expect(banner?.textContent).toContain('Asks the reader to approve the risk.');
      expect(banner!.compareDocumentPosition(el.querySelector('form')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('keeps Confirm disabled until the form is valid', async () => {
      await load(draftExtraction());
      expect(confirmButton().disabled).toBe(true);

      await type('annualRevenue', '1,250,000');
      expect(confirmButton().disabled).toBe(false);

      for (const [key, bad, good] of [
        ['postalCode', '3360', '33605-1234'],
        ['region', 'Florida', 'fl'],
        ['naicsCode', '72251', '722513'],
        ['annualRevenue', '12.345', '1250000.5'],
      ]) {
        await type(key, bad);
        expect(confirmButton().disabled, `${key}=${bad}`).toBe(true);
        expect(control(key).getAttribute('aria-invalid')).toBe('true');
        expect(describedText(key)).not.toBe('');
        await type(key, good);
        expect(confirmButton().disabled, `${key}=${good}`).toBe(false);
      }

      await type('businessName', '   ');
      expect(confirmButton().disabled).toBe(true);
      expect(describedText('businessName')).toContain('Business name is required.');
    });

    it('confirms through the generated client and shows the submission ID', async () => {
      await load(draftExtraction());
      await type('region', 'fl');
      const req = await confirmWithRevenue('1,250,000');

      const expected: SubmissionRequest = {
        businessName: 'Coastal Taco Stand LLC',
        address: { line1: '1402 E 7th Ave', city: 'Tampa', region: 'FL', postalCode: '33605', country: 'US' },
        classOfBusiness: { naicsCode: '722513' },
        annualRevenue: { amount: '1250000', currency: 'USD' },
        requestedLimit: {
          perOccurrence: { amount: '1000000.00', currency: 'USD' },
          aggregate: { amount: '2000000.00', currency: 'USD' },
        },
        effectiveDate: '2026-12-15',
      };
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(expected);
      expect(confirmButton().disabled).toBe(true);

      req.flush(submission, { status: 201, statusText: 'Created' });
      await refresh();

      expect(el.querySelector('form')).toBeNull();
      expect(el.querySelector('[data-testid="submission-id"]')?.textContent).toBe('sub_456');
      const created = el.querySelector('[data-testid="created"]');
      expect(created?.getAttribute('role')).toBe('status');
      expect(created?.querySelector('a')?.textContent).toContain('Continue to the submission');
      expect(created?.querySelector('a')?.getAttribute('href')).toBe('/submissions/sub_456');
      // The returned submission is shown without fetching it again (verify() checks).
    });

    it('puts each 422 error on the field its pointer names', async () => {
      await load(draftExtraction());
      const problem: ValidationProblem = {
        type: 'https://api.example.com/problems/business-rule-violation',
        title: 'Submission violates a business rule',
        status: 422,
        detail: 'The submission is well-formed but cannot be accepted.',
        errors: [
          { pointer: '/effectiveDate', detail: 'Effective date 2026-09-01 is in the past.' },
          { pointer: '/classOfBusiness/naicsCode', detail: 'NAICS 213111 is outside appetite.' },
          { pointer: '/address/country', detail: 'Only US addresses are accepted.' },
        ],
      };
      (await confirmWithRevenue()).flush(problem, { status: 422, statusText: 'Unprocessable Entity' });
      await refresh();

      expect(describedText('effectiveDate')).toContain('Effective date 2026-09-01 is in the past.');
      expect(control('effectiveDate').getAttribute('aria-invalid')).toBe('true');
      expect(describedText('naicsCode')).toContain('NAICS 213111 is outside appetite.');

      const alert = el.querySelector('.form-error[role="alert"]');
      expect(alert?.textContent).toContain('Submission violates a business rule');
      expect(alert?.textContent).toContain('/address/country');
      expect(alert?.textContent).toContain('Only US addresses are accepted.');
      expect(confirmButton().disabled).toBe(true);

      // Editing the field clears its server error.
      await type('effectiveDate', '2026-12-20');
      expect(describedText('effectiveDate')).not.toContain('in the past');
    });

    it('on 409, says it was already confirmed and shows its submission', async () => {
      await load(draftExtraction());
      const problem: Problem = {
        type: 'https://api.example.com/problems/extraction-already-confirmed',
        title: 'Extraction already confirmed',
        status: 409,
      };
      (await confirmWithRevenue()).flush(problem, { status: 409, statusText: 'Conflict' });
      await refresh();

      expect(el.querySelector('[data-testid="notice"]')?.textContent).toContain('already confirmed');
      http.expectOne('/v1/extractions/ext_123').flush(confirmedExtraction());
      await refresh();
      http.expectOne('/v1/submissions/sub_456').flush(submission);
      await refresh();

      expect(el.querySelector('form')).toBeNull();
      expect(el.querySelector('[data-testid="submission-id"]')?.textContent).toBe('sub_456');
    });
  });

  describe('confirmed extraction', () => {
    let writeText: ReturnType<typeof vi.fn>;

    beforeEach(() => {
      writeText = vi.fn().mockResolvedValue(undefined);
      Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    });

    afterEach(() => {
      Reflect.deleteProperty(navigator, 'clipboard');
    });

    it('shows the confirmed submission read-only, with corrections and review notes', async () => {
      await load(confirmedExtraction());
      expect(el.textContent).toContain('Loading the submission');
      http.expectOne('/v1/submissions/sub_456').flush({ ...submission, address: { ...submission.address, city: 'Tampa Bay' } });
      await refresh();

      expect(el.querySelector('form, input, select')).toBeNull();
      const confirmed = el.querySelector('[data-testid="confirmed"]')!;
      expect(el.querySelector('[data-testid="submission-id"]')?.textContent).toBe('sub_456');
      const idLink = confirmed.querySelector<HTMLAnchorElement>('a[data-testid="submission-id"]');
      expect(idLink?.getAttribute('href')).toBe('/submissions/sub_456');
      expect(
        [...confirmed.querySelectorAll('a')].some(
          (a) => a.textContent?.includes('Get a quote') && a.getAttribute('href') === '/submissions/sub_456',
        ),
      ).toBe(true);

      const text = el.textContent!;
      expect(text).toContain('$1,250,000.00');
      expect(text).toContain('722513 (Limited-Service Restaurants)');
      expect(text).toContain('Corrected by reviewer (AI read: not found)');
      expect(text).toContain('Corrected by reviewer (AI read: Tampa)');
      // Fields the reviewer left alone carry no correction note.
      expect(el.querySelector('#value-businessName-correction')).toBeNull();
      // Flags and evidence stay visible for context.
      expect(text).toContain('financials are to follow');
      expect(text).toContain('NAICS code: 722513');

      [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Copy'))!.click();
      expect(writeText).toHaveBeenCalledWith('sub_456');
      await refresh();
      expect(el.querySelector('[role="status"]')?.textContent).toContain('Copied');
    });

    it('still shows the submission ID if the submission cannot be loaded', async () => {
      await load(confirmedExtraction());
      const problem: Problem = { type: 'https://api.example.com/problems/not-found', title: 'Resource not found', status: 404 };
      http.expectOne('/v1/submissions/sub_456').flush(problem, { status: 404, statusText: 'Not Found' });
      await refresh();

      expect(el.querySelector('[data-testid="submission-id"]')?.textContent).toBe('sub_456');
      expect(el.querySelector('[role="alert"]')?.textContent).toContain('404 Resource not found');
    });
  });
});
