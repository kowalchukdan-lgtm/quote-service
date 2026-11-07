import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { ExtractionsService } from '../api';
import { problemOf } from '../problem';

// Matches spring.servlet.multipart.max-file-size=10MB, which Spring reads as 10 MiB.
export const MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;

const MB = 1024 * 1024;

/** Returns why the file can't be uploaded, or undefined if it can. */
export function checkDocument(file: File): string | undefined {
  const pdfByName = file.name.toLowerCase().endsWith('.pdf');
  // Some systems report no MIME type; fall back to the extension only then.
  const isPdf = file.type === 'application/pdf' || (file.type === '' && pdfByName);
  if (!isPdf) {
    return `“${file.name}” isn't a PDF. Choose a PDF document.`;
  }
  if (file.size > MAX_DOCUMENT_BYTES) {
    return `“${file.name}” is ${(file.size / MB).toFixed(1)} MB; the limit is 10 MB.`;
  }
  return undefined;
}

interface UploadError {
  title: string;
  detail?: string;
  retryable: boolean;
}

// Used when the error body isn't Problem Details (a proxy's HTML page, a network failure).
const FALLBACK: Record<number, Omit<UploadError, 'retryable'>> = {
  413: { title: 'Document too large', detail: 'The limit is 10 MB.' },
  422: {
    title: 'Document could not be read',
    detail: 'It may be a scan without a text layer, password-protected, or too long.',
  },
  502: { title: 'Extraction failed', detail: 'The AI model did not return a usable draft.' },
  503: { title: 'Extraction not available', detail: 'Document extraction is not enabled right now.' },
};

function toUploadError(error: unknown): UploadError {
  const status = error instanceof HttpErrorResponse ? error.status : 0;
  const problem = problemOf(error);
  const retryable = status === 502;
  if (problem) {
    return { title: problem.title, detail: problem.detail, retryable };
  }
  const fallback = FALLBACK[status] ?? {
    title: 'Upload failed',
    detail: 'The document could not be uploaded. Check your connection and try again.',
  };
  return { ...fallback, retryable };
}

@Component({
  selector: 'app-upload',
  templateUrl: './upload.html',
  styleUrl: './upload.scss',
})
export class Upload {
  private readonly extractions = inject(ExtractionsService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly file = signal<File | undefined>(undefined);
  protected readonly reading = signal(false);
  protected readonly dragging = signal(false);
  protected readonly rejection = signal<string | undefined>(undefined);
  protected readonly error = signal<UploadError | undefined>(undefined);

  protected sizeInMb(file: File): string {
    return (file.size / MB).toFixed(1);
  }

  protected onPick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    // Clear the native input so picking the same file again still fires `change`.
    input.value = '';
    if (file) {
      this.choose(file);
    }
  }

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    if (!this.reading()) {
      this.dragging.set(true);
    }
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file && !this.reading()) {
      this.choose(file);
    }
  }

  protected submit(event?: Event): void {
    event?.preventDefault();
    const file = this.file();
    if (!file || this.reading()) {
      return;
    }
    this.reading.set(true);
    this.error.set(undefined);
    this.extractions
      .createExtraction(file)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (extraction) => void this.router.navigate(['/extractions', extraction.id]),
        error: (error: unknown) => {
          this.reading.set(false);
          this.error.set(toUploadError(error));
        },
      });
  }

  private choose(file: File): void {
    this.error.set(undefined);
    const rejection = checkDocument(file);
    this.rejection.set(rejection);
    this.file.set(rejection ? undefined : file);
  }
}
