import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { Extraction, Problem, provideApi } from '../api';
import { API_BASE_PATH } from '../app.config';
import { MAX_DOCUMENT_BYTES, Upload } from './upload';

const URL = '/v1/extractions';

const created: Extraction = {
  id: 'ext_123',
  status: 'DRAFT',
  document: { fileName: 'application.pdf', sizeBytes: 3011, sha256: 'a'.repeat(64) },
  draft: {},
  flags: [],
  evidence: [],
  missingFields: [],
  model: 'gpt-5-mini-2025-08-07',
  createdAt: '2026-11-07T17:02:41Z',
};

function pdf(name = 'application.pdf', size?: number): File {
  const file = new File(['%PDF-1.7'], name, { type: 'application/pdf' });
  if (size !== undefined) {
    // Report a large size without allocating it.
    Object.defineProperty(file, 'size', { value: size });
  }
  return file;
}

function problem(status: number, title: string, detail: string): Problem {
  return { type: `https://api.example.com/problems/${status}`, title, status, detail };
}

describe('Upload', () => {
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;
  let fixture: ComponentFixture<Upload>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Upload],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideApi(API_BASE_PATH), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture = TestBed.createComponent(Upload);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  const input = () => el.querySelector<HTMLInputElement>('input[type="file"]')!;
  const readButton = () =>
    [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Read document'))!;
  const alert = () => el.querySelector('[role="alert"]');

  function pick(file: File) {
    Object.defineProperty(input(), 'files', { value: [file], configurable: true });
    input().dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  function upload(file = pdf()) {
    pick(file);
    readButton().click();
    fixture.detectChanges();
    return http.expectOne(URL);
  }

  function fail(status: number, body: Problem | string) {
    upload().flush(body, { status, statusText: 'Error' });
    fixture.detectChanges();
  }

  it('has a labelled, keyboard-reachable file picker', () => {
    const label = el.querySelector(`label[for="${input().id}"]`);
    expect(label?.textContent).toContain('PDF');
    expect(input().tabIndex).not.toBe(-1);
    expect(readButton().disabled).toBe(true);
  });

  it('uploads the PDF, shows the reading state, then opens the extraction', () => {
    const file = pdf();
    const req = upload(file);

    expect(req.request.method).toBe('POST');
    expect((req.request.body as FormData).get('document')).toBe(file);
    expect(readButton().disabled).toBe(true);
    expect(input().disabled).toBe(true);
    expect(el.querySelector('[role="status"]')?.textContent).toContain('Reading the document');

    req.flush(created, { status: 201, statusText: 'Created' });
    expect(navigate).toHaveBeenCalledWith(['/extractions', 'ext_123']);
  });

  it.each([
    [413, 'Document too large', 'The document is 12.4 MB; the limit is 10 MB.'],
    [422, 'Unreadable document', 'The PDF has no text layer; it may be a scan.'],
    [502, 'Extraction failed', 'The AI model returned an unusable answer.'],
    [503, 'Service unavailable', 'Document extraction is not enabled.'],
  ])('shows the Problem Details for a %i', (status, title, detail) => {
    fail(status, problem(status, title, detail));

    expect(alert()?.textContent).toContain(title);
    expect(alert()?.textContent).toContain(detail);
    expect(readButton().disabled).toBe(false);
    expect(el.querySelector('[data-testid="retry"]') !== null).toBe(status === 502);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('retries the same document after a 502', () => {
    const file = pdf();
    upload(file).flush(problem(502, 'Extraction failed', 'Try again.'), { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();

    el.querySelector<HTMLButtonElement>('[data-testid="retry"]')!.click();
    fixture.detectChanges();
    const retry = http.expectOne(URL);
    expect((retry.request.body as FormData).get('document')).toBe(file);
    expect(alert()).toBeNull();

    retry.flush(created, { status: 201, statusText: 'Created' });
    expect(navigate).toHaveBeenCalledWith(['/extractions', 'ext_123']);
  });

  it('explains a 413 even when the body is not Problem Details', () => {
    fail(413, '<html>Request Entity Too Large</html>');
    expect(alert()?.textContent).toContain('Document too large');
  });

  it('rejects a non-PDF without uploading it', () => {
    pick(new File(['hello'], 'notes.txt', { type: 'text/plain' }));

    expect(alert()?.textContent).toContain('“notes.txt” isn\'t a PDF');
    expect(readButton().disabled).toBe(true);
    http.expectNone(URL);
  });

  it('rejects a PDF over 10 MB without uploading it', () => {
    pick(pdf('big.pdf', MAX_DOCUMENT_BYTES + 1));

    expect(alert()?.textContent).toContain('the limit is 10 MB');
    expect(readButton().disabled).toBe(true);
    http.expectNone(URL);
  });

  it('accepts a PDF dropped on the drop zone', () => {
    const file = pdf('dropped.pdf');
    const drop = new Event('drop', { cancelable: true });
    Object.defineProperty(drop, 'dataTransfer', { value: { files: [file] } });
    el.querySelector('[data-testid="drop-zone"]')!.dispatchEvent(drop);
    fixture.detectChanges();

    expect(drop.defaultPrevented).toBe(true);
    expect(el.querySelector('[data-testid="chosen"]')?.textContent).toContain('dropped.pdf');
    expect(readButton().disabled).toBe(false);
  });
});
