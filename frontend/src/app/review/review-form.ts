import { Component, DestroyRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Extraction, ExtractionsService, FieldError, Submission } from '../api';
import { problemOf } from '../problem';
import { FieldNotes, FieldNotesContent, describedBy } from './field-notes';
import {
  FIELD,
  FIELD_GROUPS,
  FieldDef,
  FieldKey,
  LIMIT_OPTIONS,
  ReviewFormValue,
  draftToForm,
  matchField,
  reviewInfo,
  toRequest,
} from './review-fields';
import { ReviewNotices } from './review-notices';

interface FormError {
  title: string;
  detail?: string;
  unplaced: FieldError[];
}

function control(key: FieldKey): FormControl<string> {
  return new FormControl('', { nonNullable: true, validators: FIELD[key].validators });
}

/** The underwriter's review of a DRAFT extraction, confirmed as a submission. */
@Component({
  selector: 'app-review-form',
  imports: [ReactiveFormsModule, FieldNotes, ReviewNotices],
  templateUrl: './review-form.html',
  styleUrl: './review-form.scss',
})
export class ReviewForm {
  private readonly extractions = inject(ExtractionsService);
  private readonly destroyRef = inject(DestroyRef);

  readonly extraction = input.required<Extraction>();
  readonly confirmed = output<Submission>();
  readonly alreadyConfirmed = output<void>();

  protected readonly groups = FIELD_GROUPS;
  protected readonly limitOptions = LIMIT_OPTIONS;
  protected readonly info = computed(() => reviewInfo(this.extraction()));
  protected readonly confirming = signal(false);
  protected readonly formError = signal<FormError | undefined>(undefined);

  // Shaped like SubmissionRequest; country and currencies are fixed (US, USD) and added on send.
  protected readonly form = new FormGroup({
    businessName: control('businessName'),
    address: new FormGroup({
      line1: control('line1'),
      line2: control('line2'),
      city: control('city'),
      region: control('region'),
      postalCode: control('postalCode'),
    }),
    classOfBusiness: new FormGroup({ naicsCode: control('naicsCode') }),
    annualRevenue: new FormGroup({ amount: control('annualRevenue') }),
    requestedLimit: control('requestedLimit'),
    effectiveDate: control('effectiveDate'),
  });

  constructor() {
    // Pre-fill from the draft, once per extraction, so a re-render never discards edits.
    let filledFor: string | undefined;
    effect(() => {
      const extraction = this.extraction();
      if (extraction.id !== filledFor) {
        filledFor = extraction.id;
        untracked(() => {
          this.form.reset(draftToForm(extraction.draft));
          this.formError.set(undefined);
        });
      }
    });
  }

  protected control(field: FieldDef): FormControl<string> {
    return this.form.get([...field.path]) as FormControl<string>;
  }

  protected inputId(field: FieldDef): string {
    return `field-${field.key}`;
  }

  protected flagState(field: FieldDef): 'missing' | 'review' | '' {
    const flags = this.info().byField.get(field.key)?.flags ?? [];
    if (flags.some((f) => f.reason === 'MISSING')) {
      return 'missing';
    }
    return flags.length ? 'review' : '';
  }

  protected errorMessage(field: FieldDef): string | undefined {
    const c = this.control(field);
    const errors = c.errors;
    if (!errors) {
      return undefined;
    }
    if (errors['server']) {
      return errors['server'] as string;
    }
    if (!c.touched && !c.dirty) {
      return undefined;
    }
    if (errors['required']) {
      return `${field.label.replace(' (optional)', '')} is required.`;
    }
    if (errors['maxlength']) {
      return `Use at most ${(errors['maxlength'] as { requiredLength: number }).requiredLength} characters.`;
    }
    return field.formatMessage ?? 'Check this value.';
  }

  protected notes(field: FieldDef): FieldNotesContent {
    return {
      hint: field.hint,
      review: this.info().byField.get(field.key),
      error: this.errorMessage(field),
    };
  }

  protected describedBy(field: FieldDef): string | null {
    return describedBy(this.inputId(field), this.notes(field));
  }

  protected confirm(event: Event): void {
    event.preventDefault();
    if (this.form.invalid || this.confirming()) {
      return;
    }
    this.confirming.set(true);
    this.formError.set(undefined);
    const request = toRequest(this.form.getRawValue() as ReviewFormValue);
    this.extractions
      .confirmExtraction(this.extraction().id, request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (submission) => {
          this.confirming.set(false);
          this.confirmed.emit(submission);
        },
        error: (error: unknown) => {
          this.confirming.set(false);
          if (error instanceof HttpErrorResponse && error.status === 409) {
            this.alreadyConfirmed.emit();
            return;
          }
          this.showError(error);
        },
      });
  }

  private showError(error: unknown): void {
    const problem = problemOf(error);
    const unplaced: FieldError[] = [];
    for (const fieldError of problem?.errors ?? []) {
      const field = matchField(fieldError.pointer);
      if (field) {
        const c = this.control(field);
        c.setErrors({ ...c.errors, server: fieldError.detail });
        c.markAsTouched();
      } else {
        unplaced.push(fieldError);
      }
    }
    this.formError.set({
      title: problem?.title ?? 'The submission could not be confirmed.',
      detail: problem?.detail ?? (problem ? undefined : 'Check your connection and try again.'),
      unplaced,
    });
  }
}
