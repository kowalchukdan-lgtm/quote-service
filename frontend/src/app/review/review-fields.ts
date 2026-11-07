import { AbstractControl, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import {
  Extraction,
  ExtractionEvidence,
  ExtractionFlag,
  RequestedLimitDraft,
  SubmissionDraft,
  SubmissionRequest,
} from '../api';

// Review screen helpers: which form field each JSON Pointer belongs to, and how draft,
// form and submission values map onto each other. No Angular state here.

export type FieldKey =
  | 'businessName'
  | 'line1'
  | 'line2'
  | 'city'
  | 'region'
  | 'postalCode'
  | 'naicsCode'
  | 'annualRevenue'
  | 'requestedLimit'
  | 'effectiveDate';

export interface FieldDef {
  key: FieldKey;
  label: string;
  /** JSON Pointer this field owns; flags, evidence and errors at or below it land here. */
  pointer: string;
  /** Path of the control in the form, which is shaped like SubmissionRequest. */
  path: readonly string[];
  kind: 'text' | 'date' | 'limit';
  hint?: string;
  validators: ValidatorFn[];
  /** Message for a failed format check. */
  formatMessage?: string;
  /** Reads the field's value, normalised for comparison, from a draft or a submission. */
  read: (source: SubmissionDraft) => string | undefined;
  /** Formats a value returned by `read` for display. */
  show?: (value: string) => string;
}

export interface LimitOption {
  value: string;
  perOccurrence: string;
  aggregate: string;
  label: string;
}

// The offered combinations, from the API description's business rules.
export const LIMIT_OPTIONS: readonly LimitOption[] = [
  {
    value: '1000000.00/2000000.00',
    perOccurrence: '1000000.00',
    aggregate: '2000000.00',
    label: '$1,000,000 per occurrence / $2,000,000 aggregate',
  },
  {
    value: '2000000.00/4000000.00',
    perOccurrence: '2000000.00',
    aggregate: '4000000.00',
    label: '$2,000,000 per occurrence / $4,000,000 aggregate',
  },
];

const AMOUNT = /^\d{1,13}(\.\d{1,2})?$/;

const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

export function formatUsd(amount: string): string {
  return usd.format(Number(amount));
}

/** Removes the thousands separators a person may type; the contract wants plain digits. */
export function plainAmount(value: string): string {
  return value.replace(/[,\s]/g, '');
}

/** Canonical form of a decimal amount, so 1000000 and 1000000.00 compare equal. */
function canonicalAmount(amount: string | undefined): string | undefined {
  return amount !== undefined && AMOUNT.test(amount) ? Number(amount).toFixed(2) : undefined;
}

function text(value: string | undefined): string | undefined {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

/** The limit pair as `per/aggregate`, whether or not it is an offered one. */
export function limitValue(limit: RequestedLimitDraft | undefined): string | undefined {
  const per = canonicalAmount(limit?.perOccurrence?.amount);
  const aggregate = canonicalAmount(limit?.aggregate?.amount);
  return per && aggregate ? `${per}/${aggregate}` : undefined;
}

function showLimit(value: string): string {
  const offered = LIMIT_OPTIONS.find((o) => o.value === value);
  if (offered) {
    return offered.label;
  }
  const [per, aggregate] = value.split('/');
  return `${formatUsd(per)} per occurrence / ${formatUsd(aggregate)} aggregate`;
}

const notBlank: ValidatorFn = (control: AbstractControl): ValidationErrors | null =>
  typeof control.value === 'string' && control.value.length > 0 && control.value.trim() === ''
    ? { required: true }
    : null;

const amount: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = plainAmount(String(control.value ?? ''));
  return value === '' || AMOUNT.test(value) ? null : { format: true };
};

function pattern(regex: RegExp): ValidatorFn {
  return (control) => (control.value === '' || regex.test(control.value) ? null : { format: true });
}

export const FIELDS: readonly FieldDef[] = [
  {
    key: 'businessName',
    label: 'Business name',
    pointer: '/businessName',
    path: ['businessName'],
    kind: 'text',
    validators: [Validators.required, notBlank, Validators.maxLength(200)],
    read: (d) => text(d.businessName),
  },
  {
    key: 'line1',
    label: 'Address line 1',
    pointer: '/address/line1',
    path: ['address', 'line1'],
    kind: 'text',
    validators: [Validators.required, notBlank, Validators.maxLength(100)],
    read: (d) => text(d.address?.line1),
  },
  {
    key: 'line2',
    label: 'Address line 2 (optional)',
    pointer: '/address/line2',
    path: ['address', 'line2'],
    kind: 'text',
    validators: [Validators.maxLength(100)],
    read: (d) => text(d.address?.line2),
  },
  {
    key: 'city',
    label: 'City',
    pointer: '/address/city',
    path: ['address', 'city'],
    kind: 'text',
    validators: [Validators.required, notBlank, Validators.maxLength(100)],
    read: (d) => text(d.address?.city),
  },
  {
    key: 'region',
    label: 'State',
    pointer: '/address/region',
    path: ['address', 'region'],
    kind: 'text',
    hint: 'Two-letter code, such as FL.',
    validators: [Validators.required, pattern(/^\s*[A-Za-z]{2}\s*$/)],
    formatMessage: 'Enter a two-letter state code, such as FL.',
    read: (d) => text(d.address?.region)?.toUpperCase(),
  },
  {
    key: 'postalCode',
    label: 'ZIP code',
    pointer: '/address/postalCode',
    path: ['address', 'postalCode'],
    kind: 'text',
    hint: '5 digits, or ZIP+4 such as 33605-1234.',
    validators: [Validators.required, pattern(/^\s*\d{5}(-\d{4})?\s*$/)],
    formatMessage: 'Enter a 5-digit ZIP code, or ZIP+4 such as 33605-1234.',
    read: (d) => text(d.address?.postalCode),
  },
  {
    key: 'naicsCode',
    label: 'NAICS code',
    pointer: '/classOfBusiness',
    path: ['classOfBusiness', 'naicsCode'],
    kind: 'text',
    hint: 'Six digits (2022 NAICS).',
    validators: [Validators.required, pattern(/^\s*\d{6}\s*$/)],
    formatMessage: 'Enter a six-digit NAICS code.',
    read: (d) => text(d.classOfBusiness?.naicsCode),
  },
  {
    key: 'annualRevenue',
    label: 'Annual revenue (USD)',
    pointer: '/annualRevenue',
    path: ['annualRevenue', 'amount'],
    kind: 'text',
    hint: 'In US dollars, such as 1,250,000.',
    validators: [Validators.required, amount],
    formatMessage: 'Enter an amount in dollars, with at most two decimal places.',
    read: (d) => canonicalAmount(d.annualRevenue?.amount),
    show: formatUsd,
  },
  {
    key: 'requestedLimit',
    label: 'Limits',
    pointer: '/requestedLimit',
    path: ['requestedLimit'],
    kind: 'limit',
    validators: [Validators.required],
    read: (d) => limitValue(d.requestedLimit),
    show: showLimit,
  },
  {
    key: 'effectiveDate',
    label: 'Effective date',
    pointer: '/effectiveDate',
    path: ['effectiveDate'],
    kind: 'date',
    validators: [Validators.required],
    read: (d) => text(d.effectiveDate),
  },
];

export const FIELD: Readonly<Record<FieldKey, FieldDef>> = Object.fromEntries(
  FIELDS.map((f) => [f.key, f]),
) as Record<FieldKey, FieldDef>;

export const FIELD_GROUPS: readonly { legend: string; fields: readonly FieldDef[] }[] = [
  { legend: 'Business', fields: [FIELD.businessName] },
  {
    legend: 'Address (United States)',
    fields: [FIELD.line1, FIELD.line2, FIELD.city, FIELD.region, FIELD.postalCode],
  },
  {
    legend: 'Coverage',
    fields: [FIELD.naicsCode, FIELD.annualRevenue, FIELD.requestedLimit, FIELD.effectiveDate],
  },
];

/** The field a JSON Pointer belongs to: an exact match, or the field's pointer as a segment prefix. */
export function matchField(pointer: string): FieldDef | undefined {
  return FIELDS.find((f) => pointer === f.pointer || pointer.startsWith(f.pointer + '/'));
}

export interface FieldReview {
  flags: ExtractionFlag[];
  evidence: ExtractionEvidence[];
}

export interface ReviewInfo {
  byField: ReadonlyMap<FieldKey, FieldReview>;
  /** Flags about the whole document (empty pointer), such as SUSPECTED_INSTRUCTION. */
  documentFlags: ExtractionFlag[];
  /** Flags whose pointer matches no field, so they are not lost. */
  unplaced: ExtractionFlag[];
  /** Set when the draft's address is outside the US, which the form can't express. */
  countryNote?: string;
  /** Set when the document asks for limits that aren't offered. */
  limitNote?: string;
}

export function reviewInfo(extraction: Extraction): ReviewInfo {
  const flags = [...extraction.flags];
  for (const pointer of extraction.missingFields) {
    if (!flags.some((f) => f.field === pointer && f.reason === 'MISSING')) {
      flags.push({ field: pointer, reason: 'MISSING' });
    }
  }

  const byField = new Map<FieldKey, FieldReview>();
  const entry = (key: FieldKey) => {
    let review = byField.get(key);
    if (!review) {
      review = { flags: [], evidence: [] };
      byField.set(key, review);
    }
    return review;
  };

  const documentFlags: ExtractionFlag[] = [];
  const unplaced: ExtractionFlag[] = [];
  for (const flag of flags) {
    const field = flag.field === '' ? undefined : matchField(flag.field);
    if (field) {
      entry(field.key).flags.push(flag);
    } else if (flag.field === '') {
      documentFlags.push(flag);
    } else {
      unplaced.push(flag);
    }
  }
  for (const evidence of extraction.evidence) {
    const field = matchField(evidence.field);
    if (field) {
      entry(field.key).evidence.push(evidence);
    }
  }

  const draft = extraction.draft;
  const country = text(draft.address?.country)?.toUpperCase();
  const limit = limitValue(draft.requestedLimit);
  return {
    byField,
    documentFlags,
    unplaced,
    countryNote:
      country && country !== 'US'
        ? `The document gives the country as ${country}. Only US addresses can be quoted.`
        : undefined,
    limitNote:
      limit && !LIMIT_OPTIONS.some((o) => o.value === limit)
        ? `The document asks for ${showLimit(limit)}, which isn't offered.`
        : undefined,
  };
}

export interface ReviewFormValue {
  businessName: string;
  address: { line1: string; line2: string; city: string; region: string; postalCode: string };
  classOfBusiness: { naicsCode: string };
  annualRevenue: { amount: string };
  requestedLimit: string;
  effectiveDate: string;
}

export function draftToForm(draft: SubmissionDraft): ReviewFormValue {
  const value = (key: FieldKey) => FIELD[key].read(draft) ?? '';
  const limit = value('requestedLimit');
  return {
    businessName: value('businessName'),
    address: {
      line1: value('line1'),
      line2: value('line2'),
      city: value('city'),
      region: value('region'),
      postalCode: value('postalCode'),
    },
    classOfBusiness: { naicsCode: value('naicsCode') },
    annualRevenue: { amount: value('annualRevenue') },
    requestedLimit: LIMIT_OPTIONS.some((o) => o.value === limit) ? limit : '',
    effectiveDate: value('effectiveDate'),
  };
}

export function toRequest(form: ReviewFormValue): SubmissionRequest {
  const limit = LIMIT_OPTIONS.find((o) => o.value === form.requestedLimit);
  if (!limit) {
    throw new Error(`Not an offered limit: ${form.requestedLimit}`);
  }
  const line2 = form.address.line2.trim();
  return {
    businessName: form.businessName.trim(),
    address: {
      line1: form.address.line1.trim(),
      ...(line2 ? { line2 } : {}),
      city: form.address.city.trim(),
      region: form.address.region.trim().toUpperCase(),
      postalCode: form.address.postalCode.trim(),
      country: 'US',
    },
    classOfBusiness: { naicsCode: form.classOfBusiness.naicsCode.trim() },
    annualRevenue: { amount: plainAmount(form.annualRevenue.amount), currency: 'USD' },
    requestedLimit: {
      perOccurrence: { amount: limit.perOccurrence, currency: 'USD' },
      aggregate: { amount: limit.aggregate, currency: 'USD' },
    },
    effectiveDate: form.effectiveDate,
  };
}

/** Displays a field's value from a draft or submission, or undefined when it has none. */
export function display(field: FieldDef, source: SubmissionDraft): string | undefined {
  const value = field.read(source);
  return value === undefined ? undefined : (field.show?.(value) ?? value);
}

/** Notes for fields the reviewer changed from what the AI read. */
export function corrections(
  draft: SubmissionDraft,
  submitted: SubmissionDraft,
): ReadonlyMap<FieldKey, string> {
  const notes = new Map<FieldKey, string>();
  for (const field of FIELDS) {
    if (field.read(draft) !== field.read(submitted)) {
      notes.set(field.key, `Corrected by reviewer (AI read: ${display(field, draft) ?? 'not found'})`);
    }
  }
  return notes;
}
