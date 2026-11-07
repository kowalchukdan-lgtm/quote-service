import { Extraction } from '../api';
import { corrections, draftToForm, matchField, reviewInfo, toRequest } from './review-fields';

function extraction(overrides: Partial<Extraction>): Extraction {
  return {
    id: 'ext_1',
    status: 'DRAFT',
    document: { fileName: 'a.pdf', sizeBytes: 1, sha256: 'a'.repeat(64) },
    draft: {},
    flags: [],
    evidence: [],
    missingFields: [],
    model: 'm',
    createdAt: '2026-11-07T00:00:00Z',
    ...overrides,
  };
}

describe('review fields', () => {
  it('matches a pointer to its field by whole segments', () => {
    expect(matchField('/annualRevenue')?.key).toBe('annualRevenue');
    expect(matchField('/annualRevenue/amount')?.key).toBe('annualRevenue');
    expect(matchField('/classOfBusiness/naicsCode')?.key).toBe('naicsCode');
    expect(matchField('/requestedLimit/aggregate/amount')?.key).toBe('requestedLimit');
    expect(matchField('/address/postalCode')?.key).toBe('postalCode');
    expect(matchField('/businessNameX')).toBeUndefined();
    expect(matchField('/address')).toBeUndefined();
    expect(matchField('')).toBeUndefined();
  });

  it('sorts flags into fields, the document banner and an also-check list', () => {
    const info = reviewInfo(
      extraction({
        flags: [
          { field: '', reason: 'SUSPECTED_INSTRUCTION' },
          { field: '/address', reason: 'MISSING' },
          { field: '/effectiveDate', reason: 'AMBIGUOUS' },
        ],
        missingFields: ['/annualRevenue', '/address'],
      }),
    );
    expect(info.documentFlags.map((f) => f.reason)).toEqual(['SUSPECTED_INSTRUCTION']);
    expect(info.unplaced.map((f) => f.field)).toEqual(['/address']);
    expect(info.byField.get('effectiveDate')?.flags[0].reason).toBe('AMBIGUOUS');
    expect(info.byField.get('annualRevenue')?.flags[0].reason).toBe('MISSING');
  });

  it('notes limits that are not offered and leaves the select empty', () => {
    const draft = {
      requestedLimit: {
        perOccurrence: { amount: '500000', currency: 'USD' },
        aggregate: { amount: '1000000', currency: 'USD' },
      },
    };
    expect(reviewInfo(extraction({ draft })).limitNote).toContain('$500,000.00 per occurrence');
    expect(draftToForm(draft).requestedLimit).toBe('');
  });

  it('recognises an offered limit pair written without decimals', () => {
    const draft = {
      requestedLimit: {
        perOccurrence: { amount: '2000000', currency: 'USD' },
        aggregate: { amount: '4000000', currency: 'USD' },
      },
    };
    expect(draftToForm(draft).requestedLimit).toBe('2000000.00/4000000.00');
  });

  it('notes a non-US country rather than dropping it silently', () => {
    const info = reviewInfo(extraction({ draft: { address: { country: 'CA' } } }));
    expect(info.countryNote).toContain('CA');
  });

  it('builds a request with line 2 only when given', () => {
    const form = {
      businessName: ' Acme ',
      address: { line1: '1 Main St', line2: ' ', city: 'Tampa', region: 'fl', postalCode: '33605' },
      classOfBusiness: { naicsCode: '722513' },
      annualRevenue: { amount: '1,000' },
      requestedLimit: '1000000.00/2000000.00',
      effectiveDate: '2026-12-15',
    };
    const request = toRequest(form);
    expect(request.businessName).toBe('Acme');
    expect(request.address).toEqual({ line1: '1 Main St', city: 'Tampa', region: 'FL', postalCode: '33605', country: 'US' });
    expect(request.annualRevenue).toEqual({ amount: '1000', currency: 'USD' });
    expect(toRequest({ ...form, address: { ...form.address, line2: 'Suite 4' } }).address.line2).toBe('Suite 4');
  });

  it('reports corrections only where values really differ', () => {
    const draft = { annualRevenue: { amount: '1000000', currency: 'USD' }, address: { region: 'fl' } };
    const same = { annualRevenue: { amount: '1000000.00', currency: 'USD' }, address: { region: 'FL' } };
    expect(corrections(draft, same).size).toBe(0);

    const changed = corrections(draft, { ...same, businessName: 'Acme' });
    expect([...changed.keys()]).toEqual(['businessName']);
    expect(changed.get('businessName')).toBe('Corrected by reviewer (AI read: not found)');
  });
});
