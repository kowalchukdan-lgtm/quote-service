import { ComponentFixture, TestBed } from '@angular/core/testing';
import { formatDate } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Problem, Quote, Submission, ValidationProblem, provideApi } from '../api';
import { API_BASE_PATH } from '../app.config';
import { EXPIRY_FORMAT } from './quote-result';
import { SubmissionPage } from './submission-page';

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

// Shaped like the contract's QuoteQuoted example.
const quoted: Quote = {
  id: 'qte_1',
  submissionId: 'sub_456',
  status: 'QUOTED',
  premium: { amount: '2847.50', currency: 'USD' },
  ratingFactors: [
    { code: 'CLASS', description: 'Class of business', inputValue: '722511', factor: '1.2500' },
    { code: 'TERRITORY', description: 'Rating territory', inputValue: 'TX-201', factor: '1.0500' },
    { code: 'REVENUE_BAND', description: 'Annual revenue band', inputValue: '1000000-2499999', factor: '0.9500' },
    { code: 'LIMIT', description: 'Limit combination', inputValue: '1000000/2000000', factor: '1.0000' },
  ],
  statusReasons: [],
  createdAt: '2026-10-23T14:05:13Z',
  expiresAt: '2026-11-22T14:05:13Z',
};

const QUOTES_URL = '/v1/submissions/sub_456/quotes';

describe('SubmissionPage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<SubmissionPage>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [SubmissionPage],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideApi(API_BASE_PATH)],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  // Resources deliver a flushed response asynchronously; let them settle, then re-render.
  async function refresh() {
    for (let i = 0; i < 3; i++) {
      await new Promise((resolve) => setTimeout(resolve));
      fixture.detectChanges();
    }
  }

  function render(id = 'sub_456') {
    fixture = TestBed.createComponent(SubmissionPage);
    el = fixture.nativeElement as HTMLElement;
    fixture.componentRef.setInput('submissionId', id);
    fixture.detectChanges();
  }

  async function load() {
    render();
    http.expectOne('/v1/submissions/sub_456').flush(submission);
    await refresh();
  }

  const button = () => el.querySelector<HTMLButtonElement>('button[type="submit"]')!;
  const result = () => el.querySelector<HTMLElement>('[data-testid="quote"]');
  const text = (selector: string) => el.querySelector(selector)?.textContent?.trim();

  async function requestQuote(): Promise<TestRequest> {
    button().click();
    await refresh();
    return http.expectOne(QUOTES_URL);
  }

  async function getQuote(quote: Partial<Quote>) {
    (await requestQuote()).flush({ ...quoted, ...quote }, { status: 201, statusText: 'Created' });
    await refresh();
  }

  it('loads the submission and shows it read-only', async () => {
    render();
    expect(el.textContent).toContain('Loading');
    const req = http.expectOne('/v1/submissions/sub_456');
    expect(req.request.method).toBe('GET');
    req.flush(submission);
    await refresh();

    expect(text('h1')).toBe('Coastal Taco Stand LLC');
    expect(text('[data-testid="submission-id"]')).toBe('sub_456');
    expect(el.textContent).toContain('722513 (Limited-Service Restaurants)');
    expect(el.textContent).toContain('$1,250,000.00');
    expect(el.textContent).toContain('$1,000,000 per occurrence / $2,000,000 aggregate');
    expect(el.querySelector('input, select')).toBeNull();
    expect(button().textContent?.trim()).toBe('Get quote');
  });

  it('shows the Problem Details when the submission cannot be loaded', async () => {
    render('sub_missing');
    const problem: Problem = { type: 'https://api.example.com/problems/not-found', title: 'Resource not found', status: 404 };
    http.expectOne('/v1/submissions/sub_missing').flush(problem, { status: 404, statusText: 'Not Found' });
    await refresh();
    expect(text('[role="alert"]')).toContain('404 Resource not found');
  });

  it('shows a QUOTED premium, its rating factors and the expiry in local time', async () => {
    await load();
    const req = await requestQuote();

    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(button().disabled).toBe(true);
    expect(text('[role="status"]')).toContain('Requesting a quote');

    req.flush(quoted, { status: 201, statusText: 'Created' });
    await refresh();

    expect(text('[data-testid="outcome"]')).toBe('Quoted');
    expect(text('[data-testid="premium"]')).toBe('$2,847.50');

    const rows = [...result()!.querySelectorAll('tbody tr')].map((tr) =>
      [...tr.querySelectorAll('td')].map((td) => td.textContent?.trim()),
    );
    expect(rows).toEqual([
      ['Class of business', '722511', '1.2500'],
      ['Rating territory', 'TX-201', '1.0500'],
      ['Annual revenue band', '1000000-2499999', '0.9500'],
      ['Limit combination', '1000000/2000000', '1.0000'],
    ]);
    expect(result()!.querySelectorAll('th[scope="col"]').length).toBe(3);

    const expires = el.querySelector('time[data-testid="expires"]')!;
    expect(expires.getAttribute('datetime')).toBe('2026-11-22T14:05:13Z');
    // DatePipe without a timezone renders in the viewer's zone; the test runs in whatever zone the machine has.
    expect(expires.textContent).toBe(formatDate('2026-11-22T14:05:13Z', EXPIRY_FORMAT, 'en-US'));
    expect(expires.textContent).toMatch(/\d{1,2}:\d{2} (AM|PM)/);

    expect(document.activeElement).toBe(el.querySelector('#quote-heading'));
    expect(button().disabled).toBe(false);
    expect(button().textContent?.trim()).toBe('Get a new quote');
  });

  it.each([
    ['REFERRED', 'Referred: needs underwriter review', 'Annual revenue exceeds the automatic quoting limit for this class.'],
    ['DECLINED', 'Declined', 'We do not currently write this class in the risk\'s rating territory.'],
  ] as const)('shows the reasons for %s, with no premium', async (status, outcome, message) => {
    await load();
    await getQuote({
      status,
      premium: undefined,
      expiresAt: undefined,
      ratingFactors: [],
      statusReasons: [{ code: 'CODE', message }],
    });

    expect(text('[data-testid="outcome"]')).toBe(outcome);
    expect(result()!.querySelector('.reasons')?.textContent).toContain(message);
    expect(el.querySelector('[data-testid="premium"]')).toBeNull();
    expect(result()!.textContent).not.toContain('Bindable');
  });

  it('shows an EXPIRED quote as not bindable, with its former premium', async () => {
    await load();
    await getQuote({ status: 'EXPIRED' });

    expect(text('[data-testid="outcome"]')).toBe('Expired: not bindable');
    expect(result()!.textContent).toContain('Premium was $2,847.50');
    expect(result()!.textContent).not.toContain('Bindable until');
  });

  it('treats an unknown status as not bindable and never shows a premium', async () => {
    await load();
    // Extensible enum: a value this client doesn't know yet.
    await getQuote({ status: 'BOUND_SOMEWHERE' as Quote['status'], statusReasons: [{ code: 'X', message: 'New outcome.' }] });

    expect(text('[data-testid="outcome"]')).toBe('Status BOUND_SOMEWHERE: not bindable');
    expect(result()!.textContent).toContain('New outcome.');
    expect(el.querySelector('[data-testid="premium"]')).toBeNull();
    expect(result()!.textContent).not.toContain('Bindable');
    expect(result()!.querySelector('table')).toBeNull();
  });

  it('shows a 422 as Problem Details with its field errors', async () => {
    await load();
    const problem: ValidationProblem = {
      type: 'https://api.example.com/problems/business-rule-violation',
      title: 'Submission violates a business rule',
      status: 422,
      detail: 'The submission is well-formed but cannot be accepted.',
      errors: [{ pointer: '/effectiveDate', detail: 'Effective date 2026-11-01 is in the past.' }],
    };
    (await requestQuote()).flush(problem, { status: 422, statusText: 'Unprocessable Entity' });
    await refresh();

    const alert = text('[role="alert"]')!;
    expect(alert).toContain('Submission violates a business rule');
    expect(alert).toContain('/effectiveDate');
    expect(alert).toContain('Effective date 2026-11-01 is in the past.');
    expect(alert).toContain("A submission can't be changed");
    expect(result()).toBeNull();
    expect(button().disabled).toBe(false);
  });

  it('reuses the Idempotency-Key when retrying a failed request, and not after a quote', async () => {
    await load();
    const unavailable: Problem = { type: 'https://api.example.com/problems/service-unavailable', title: 'Service unavailable', status: 503 };

    const first = await requestQuote();
    const key = first.request.headers.get('Idempotency-Key');
    first.flush(unavailable, { status: 503, statusText: 'Service Unavailable' });
    await refresh();
    expect(text('[role="alert"]')).toContain('Service unavailable');

    const retry = await requestQuote();
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    retry.flush(quoted, { status: 201, statusText: 'Created' });
    await refresh();
    expect(el.querySelector('[role="alert"]')).toBeNull();

    const next = await requestQuote();
    expect(next.request.headers.get('Idempotency-Key')).not.toBe(key);
    next.flush({ ...quoted, id: 'qte_2' }, { status: 201, statusText: 'Created' });
    await refresh();
    expect(result()!.textContent).toContain('qte_2');
  });
});
