import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { ExtractionsService, Problem } from '../api';

// Placeholder that proves the wiring (route -> generated client -> proxy -> API).
// It will be replaced by the review screen.
@Component({
  selector: 'app-extraction-placeholder',
  templateUrl: './extraction-placeholder.html',
  styleUrl: './extraction-placeholder.scss',
})
export class ExtractionPlaceholder {
  private readonly extractions = inject(ExtractionsService);

  // Bound from the :extractionId route parameter.
  readonly extractionId = input.required<string>();

  protected readonly extraction = rxResource({
    params: () => this.extractionId(),
    stream: ({ params: id }) => this.extractions.getExtraction(id),
  });

  protected readonly problem = computed<Problem | undefined>(() => {
    const error = this.extraction.error();
    return error instanceof HttpErrorResponse ? (error.error as Problem | null) ?? undefined : undefined;
  });
}
